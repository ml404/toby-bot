package web.profile

import java.time.Instant

/**
 * Decoupled snapshot of everything [ProfileCardRenderer] needs to draw
 * one profile card. Builds in [web.service.ProfileCardAggregator] from
 * the same JDA / UserService / TitleService / AchievementService that
 * back the existing HTML profile page, so the Discord PNG and the web
 * PNG endpoint render the same data.
 *
 * Lives in `web` (not `discord-bot`) so both consumers can reach it
 * without inverting the module-dependency direction
 * (`discord-bot -> web`). The renderer sits beside this class for the
 * same reason — `web` is the lowest module both surfaces can share.
 */
data class ProfileCardData(
    val avatarUrl: String,
    val displayName: String,
    val guildName: String,
    val level: Int,
    val xpIntoLevel: Long,
    val xpForNextLevel: Long,
    val totalXp: Long,
    val socialCredit: Long,
    val equippedTitle: TitleSnapshot?,
    /**
     * The user's three most-recently-unlocked achievements, newest first.
     * Capped at 3 by the aggregator; the renderer trusts the cap.
     */
    val recentAchievements: List<AchievementSnapshot>,
) {
    data class TitleSnapshot(
        val label: String,
        val colorHex: String?,
    )

    data class AchievementSnapshot(
        val icon: String?,
        val name: String,
        val unlockedAt: Instant,
    )
}
