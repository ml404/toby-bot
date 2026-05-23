package web.service

import common.leveling.LevelCurve
import database.service.AchievementService
import database.service.TitleService
import database.service.UserService
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.springframework.stereotype.Service
import web.profile.ProfileCardData

/**
 * Builds the [ProfileCardData] snapshot consumed by both surfaces that
 * render the profile-card PNG:
 *   - `/profile` slash command in the discord-bot module
 *   - `GET /profile/{guildId}/{discordId}/card.png` in [web.controller.ProfileController]
 *
 * **Takes [Guild] + [Member] directly rather than ids + a JDA lookup**.
 * Earlier shape (`build(discordId, guildId)` with an injected `JDA`)
 * crashed startup with a circular bean dependency:
 *
 * ```
 *   JdaListenerRegistrar -> jda -> StartUpHandler -> CommandManager
 *     -> List<Command> -> ProfileCommand -> ProfileCardAggregator -> jda
 * ```
 *
 * Spring's `CommandManager` injects every `Command` bean, so any
 * service reachable from a Command that depends on `JDA` re-enters the
 * graph through the listener-registrar branch. The fix is to keep this
 * aggregator JDA-free: callers already hold a `Guild` (from
 * `event.guild` in slash commands, from a guild-scoped controller
 * route in web) so passing it in is both cheaper and architecturally
 * cleaner than re-fetching it.
 *
 * Returns null when the user has no record in this guild — the user's
 * row may not exist yet if they've never earned XP or social credit.
 * `Guild.getMemberById` returning null is already guarded by the
 * caller before invoking this method.
 */
@Service
class ProfileCardAggregator(
    private val userService: UserService,
    private val titleService: TitleService,
    private val achievementService: AchievementService,
) {
    fun build(guild: Guild, member: Member): ProfileCardData {
        val discordId = member.idLong
        val guildId = guild.idLong
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
