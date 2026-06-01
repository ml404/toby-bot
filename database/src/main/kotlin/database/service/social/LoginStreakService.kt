package database.service.social

import database.dto.social.LoginStreakDto
import java.time.Instant

interface LoginStreakService {
    /**
     * Attempt to claim today's daily reward for [discordId] in [guildId].
     * Returns the outcome — [ClaimResult.Granted] on a fresh claim (with
     * the new streak count and any XP/credits granted) or
     * [ClaimResult.AlreadyClaimed] when the user already claimed today.
     *
     * Publishes a [common.events.social.StreakClaimedEvent] only on
     * [ClaimResult.Granted]. Same-day re-claims are silent.
     */
    fun claim(
        discordId: Long,
        guildId: Long,
        at: Instant = Instant.now(),
        channelId: Long? = null
    ): ClaimResult

    fun get(discordId: Long, guildId: Long): LoginStreakDto?

    /**
     * Preview the XP/credit reward a claim landing the user on [streak]
     * would grant — without claiming. Mirrors the on-claim reward maths
     * (same config keys, same base/per-day/cap formula) so the web profile
     * can show "claim for +X" before the user commits. [streak] is the
     * streak value *after* the hypothetical claim (e.g. a continuing claim
     * on a 4-day streak previews `streak = 5`).
     */
    fun previewReward(guildId: Long, streak: Int): RewardPreview

    /**
     * Users in [guildId] with an active streak (`current_streak > 0`)
     * that haven't claimed yet on [today]. Used by `StreakReminderJob`
     * to DM the at-risk cohort just before their streak resets at
     * midnight UTC.
     */
    fun findActiveStreaksDueForReminder(
        guildId: Long,
        today: java.time.LocalDate
    ): List<LoginStreakDto>

    data class RewardPreview(
        val xp: Long,
        val credits: Long
    )

    sealed class ClaimResult {
        data class Granted(
            val currentStreak: Int,
            val longestStreak: Int,
            val xpGranted: Long,
            val creditsGranted: Long,
            val isNewBest: Boolean
        ) : ClaimResult()

        data class AlreadyClaimed(
            val currentStreak: Int,
            val longestStreak: Int
        ) : ClaimResult()
    }
}
