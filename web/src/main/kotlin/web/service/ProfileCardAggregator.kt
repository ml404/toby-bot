package web.service

import common.leveling.LevelCurve
import database.service.AchievementService
import database.service.TitleService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.profile.ProfileCardData

/**
 * Builds the [ProfileCardData] snapshot consumed by both surfaces that
 * render the profile-card PNG:
 *   - `/profile` slash command in the discord-bot module
 *   - `GET /profile/{guildId}/{discordId}/card.png` in [web.controller.ProfileController]
 *
 * Reuses the same JDA / UserService / TitleService / AchievementService
 * stack already aggregated by [ProfileWebService] for the HTML profile
 * page, so the PNG and the existing web view never disagree on a user's
 * numbers. Returns null when the bot can't see the guild or the target
 * user isn't a member — callers translate that into a 404 / ephemeral
 * "no profile" reply.
 *
 * Lives next to [ProfileWebService] rather than in `discord-bot`
 * because every dependency it needs is already wired up here; the
 * discord-bot module pulls this via Spring DI (the
 * `discord-bot -> web` module edge already exists for the existing
 * notifier services).
 */
@Service
class ProfileCardAggregator(
    private val jda: JDA,
    private val userService: UserService,
    private val titleService: TitleService,
    private val achievementService: AchievementService,
) {
    fun build(discordId: Long, guildId: Long): ProfileCardData? {
        val guild = jda.getGuildById(guildId) ?: return null
        val member = guild.getMemberById(discordId) ?: return null
        val user = userService.getUserById(discordId, guildId)

        val xp = user?.xp ?: 0L
        val progress = LevelCurve.progress(xp)

        val equippedTitle = user?.activeTitleId?.let { titleService.getById(it) }
            ?.let { ProfileCardData.TitleSnapshot(label = it.label, colorHex = it.colorHex) }

        // Achievement views include locked entries; filter to unlocked,
        // sort newest-first, take top three. The renderer trusts the cap.
        val recent = achievementService.listFor(discordId, guildId)
            .mapNotNull { v -> v.unlockedAt?.let { v to it } }
            .sortedByDescending { it.second }
            .take(MAX_RECENT_ACHIEVEMENTS)
            .map { (view, unlockedAt) ->
                ProfileCardData.AchievementSnapshot(
                    icon = view.achievement.icon,
                    name = view.achievement.name,
                    unlockedAt = unlockedAt,
                )
            }

        return ProfileCardData(
            avatarUrl = member.effectiveAvatarUrl,
            displayName = member.effectiveName,
            guildName = guild.name,
            level = progress.level,
            xpIntoLevel = progress.xpIntoLevel,
            xpForNextLevel = progress.xpForNextLevel,
            totalXp = xp,
            socialCredit = user?.socialCredit ?: 0L,
            equippedTitle = equippedTitle,
            recentAchievements = recent,
        )
    }

    companion object {
        const val MAX_RECENT_ACHIEVEMENTS = 3
    }
}
