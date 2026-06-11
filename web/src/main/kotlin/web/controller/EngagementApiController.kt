package web.controller

import common.leveling.LevelCurve
import common.notification.NotificationChannelKind
import common.notification.Surface
import database.service.guild.AchievementService
import database.service.social.LoginStreakService
import database.service.user.UserNotificationPrefService
import database.service.user.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import web.util.GuildMembership
import web.util.discordIdOrNull
import java.time.Instant

/**
 * JSON-only REST surface for the engagement loop:
 *   - POST /api/engagement/{guildId}/daily/claim — claim today's streak.
 *   - GET  /api/engagement/{guildId}/achievements — list catalogue + user state.
 *   - GET  /api/engagement/{guildId}/notifications — current prefs (incl defaults).
 *   - POST /api/engagement/{guildId}/notifications/{kind} — opt in or out.
 *
 * Reuses [LoginStreakService] / [AchievementService] / [UserNotificationPrefService]
 * verbatim so the slash-command path (`/daily`, `/achievements`, `/notify`) and
 * the web path are guaranteed to agree on streak counts, ownership, and prefs.
 *
 * Every endpoint refuses guild access for non-members (and unauthenticated
 * requests) so a crafted POST can't claim a streak in a server the user
 * isn't in.
 */
@RestController
@RequestMapping("/api/engagement/{guildId}")
class EngagementApiController(
    private val loginStreakService: LoginStreakService,
    private val achievementService: AchievementService,
    private val notificationPrefService: UserNotificationPrefService,
    private val userService: UserService,
    private val membership: GuildMembership,
) {

    @PostMapping("/daily/claim")
    fun claimDaily(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<DailyClaimResponse> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!membership.isMember(discordId, guildId)) return ResponseEntity.status(403).build()

        // The claim mutates XP and credits in the user row; re-read it so the
        // response carries the post-claim level/XP/balance and the profile
        // page can refresh the Level and Economy cards in place — no reload.
        return when (val result = loginStreakService.claim(discordId, guildId)) {
            is LoginStreakService.ClaimResult.Granted -> ResponseEntity.ok(
                dailyClaimResponse(
                    discordId = discordId,
                    guildId = guildId,
                    status = "granted",
                    currentStreak = result.currentStreak,
                    longestStreak = result.longestStreak,
                    xpGranted = result.xpGranted,
                    creditsGranted = result.creditsGranted,
                    newBest = result.isNewBest
                )
            )
            is LoginStreakService.ClaimResult.AlreadyClaimed -> ResponseEntity.ok(
                dailyClaimResponse(
                    discordId = discordId,
                    guildId = guildId,
                    status = "already_claimed",
                    currentStreak = result.currentStreak,
                    longestStreak = result.longestStreak,
                    xpGranted = 0L,
                    creditsGranted = 0L,
                    newBest = false
                )
            )
        }
    }

    private fun dailyClaimResponse(
        discordId: Long,
        guildId: Long,
        status: String,
        currentStreak: Int,
        longestStreak: Int,
        xpGranted: Long,
        creditsGranted: Long,
        newBest: Boolean,
    ): DailyClaimResponse {
        val user = userService.getUserById(discordId, guildId)
        val totalXp = user?.xp ?: 0L
        val balance = user?.socialCredit ?: 0L
        val progress = LevelCurve.progress(totalXp)
        return DailyClaimResponse(
            status = status,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            xpGranted = xpGranted,
            creditsGranted = creditsGranted,
            newBest = newBest,
            totalXp = totalXp,
            level = progress.level,
            xpIntoLevel = progress.xpIntoLevel,
            xpForNextLevel = progress.xpForNextLevel,
            xpProgressPercent = if (progress.xpForNextLevel > 0)
                ((progress.xpIntoLevel.toDouble() / progress.xpForNextLevel) * 100).toInt().coerceIn(0, 100)
            else 100,
            balance = balance,
        )
    }

    @GetMapping("/daily")
    fun streakStatus(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<StreakStatusResponse> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!membership.isMember(discordId, guildId)) return ResponseEntity.status(403).build()

        val row = loginStreakService.get(discordId, guildId)
        return ResponseEntity.ok(
            StreakStatusResponse(
                currentStreak = row?.currentStreak ?: 0,
                longestStreak = row?.longestStreak ?: 0,
                lastClaimDate = row?.lastClaimDate?.toString(),
                totalClaims = row?.totalClaims ?: 0L
            )
        )
    }

    @GetMapping("/achievements")
    fun listAchievements(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<List<AchievementResponse>> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!membership.isMember(discordId, guildId)) return ResponseEntity.status(403).build()

        val views = achievementService.listFor(discordId, guildId)
        return ResponseEntity.ok(views.map { v ->
            AchievementResponse(
                code = v.achievement.code,
                name = v.achievement.name,
                description = v.achievement.description,
                category = v.achievement.category,
                icon = v.achievement.icon,
                threshold = v.achievement.threshold,
                progress = v.progress,
                unlocked = v.unlockedAt != null,
                unlockedAt = v.unlockedAt
            )
        })
    }

    @GetMapping("/notifications")
    fun listNotifications(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<List<NotificationPrefResponse>> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!membership.isMember(discordId, guildId)) return ResponseEntity.status(403).build()

        // Index explicit rows by (kind, surface). One response entry per
        // supported (kind, surface) — kinds that don't support a surface
        // simply don't appear for that surface (the web matrix renders a
        // placeholder cell).
        val explicit = notificationPrefService.listForUser(discordId, guildId)
            .associateBy { it.channelKind to it.surface }

        val response = NotificationChannelKind.entries.flatMap { kind ->
            kind.supportedSurfaces.map { surface ->
                val row = explicit[kind.name to surface.name]
                NotificationPrefResponse(
                    kind = kind.name,
                    surface = surface.name,
                    displayName = kind.displayName,
                    description = kind.description,
                    optIn = row?.optIn ?: kind.defaultOptIn(surface),
                    isDefault = row == null
                )
            }
        }
        return ResponseEntity.ok(response)
    }

    @PostMapping("/notifications/{kindCode}/{surfaceCode}")
    fun setNotification(
        @PathVariable guildId: Long,
        @PathVariable kindCode: String,
        @PathVariable surfaceCode: String,
        @RequestBody body: NotificationPrefUpdate,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<NotificationPrefResponse> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!membership.isMember(discordId, guildId)) return ResponseEntity.status(403).build()

        val kind = NotificationChannelKind.fromCode(kindCode)
            ?: return ResponseEntity.badRequest().build()
        val surface = runCatching { Surface.valueOf(surfaceCode.uppercase()) }.getOrNull()
            ?: return ResponseEntity.badRequest().build()
        if (!kind.supports(surface)) {
            // Service would throw IllegalArgumentException; surface as 400.
            return ResponseEntity.badRequest().build()
        }

        val row = notificationPrefService.setPref(discordId, guildId, kind, surface, body.optIn)
        return ResponseEntity.ok(
            NotificationPrefResponse(
                kind = kind.name,
                surface = surface.name,
                displayName = kind.displayName,
                description = kind.description,
                optIn = row.optIn,
                isDefault = false
            )
        )
    }

    data class DailyClaimResponse(
        val status: String,
        val currentStreak: Int,
        val longestStreak: Int,
        val xpGranted: Long,
        val creditsGranted: Long,
        val newBest: Boolean,
        // Post-claim snapshot of the user's level/XP/balance so the profile
        // page can update the Level and Economy cards without a reload.
        val totalXp: Long = 0L,
        val level: Int = 0,
        val xpIntoLevel: Long = 0L,
        val xpForNextLevel: Long = 0L,
        val xpProgressPercent: Int = 0,
        val balance: Long = 0L,
    )

    data class StreakStatusResponse(
        val currentStreak: Int,
        val longestStreak: Int,
        val lastClaimDate: String?,
        val totalClaims: Long
    )

    data class AchievementResponse(
        val code: String,
        val name: String,
        val description: String,
        val category: String,
        val icon: String?,
        val threshold: Long,
        val progress: Long,
        val unlocked: Boolean,
        val unlockedAt: Instant?
    )

    data class NotificationPrefResponse(
        val kind: String,
        val surface: String,
        val displayName: String,
        val description: String,
        val optIn: Boolean,
        val isDefault: Boolean
    )

    data class NotificationPrefUpdate(val optIn: Boolean)
}
