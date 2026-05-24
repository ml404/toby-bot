package database.service.guild

import database.dto.guild.AchievementDto

interface AchievementService {
    fun listAll(): List<AchievementDto>
    fun getByCode(code: String): AchievementDto?

    /**
     * Increment progress on a counter-based achievement and unlock it
     * if the threshold is crossed. Idempotent for already-unlocked
     * achievements (no-ops once unlocked). For one-shot achievements
     * (threshold = 1), this is equivalent to [unlock].
     *
     * Returns the resulting progress state. A non-null `unlocked` means
     * this call triggered the unlock — listeners (DM, public shoutout)
     * receive the published [common.events.AchievementUnlockedEvent].
     */
    fun progress(
        discordId: Long,
        guildId: Long,
        code: String,
        delta: Long = 1L,
        channelId: Long? = null
    ): ProgressResult

    /**
     * Force-unlock an achievement regardless of progress. Idempotent —
     * a no-op if already unlocked. Awards XP/credit rewards exactly once.
     */
    fun unlock(
        discordId: Long,
        guildId: Long,
        code: String,
        channelId: Long? = null
    ): ProgressResult

    /**
     * Set progress to an absolute value. Use this for ratchet-style
     * achievements where the source event carries the current absolute
     * state (current level, current streak), not a delta. Values are
     * clamped to `[0, threshold]`; reaching the threshold triggers the
     * unlock path (reward + event) exactly once. Idempotent once
     * unlocked. Unlike [progress] this accepts decreases — important
     * for streak-broke semantics where the display should reflect the
     * user's *current* streak, not their high-water mark.
     */
    fun setProgress(
        discordId: Long,
        guildId: Long,
        code: String,
        value: Long,
        channelId: Long? = null
    ): ProgressResult

    /**
     * Snapshot for the user's profile / `/achievements` view. Includes
     * locked entries (unless hidden) so the user sees what's available.
     */
    fun listFor(discordId: Long, guildId: Long): List<AchievementView>

    /**
     * Per-(discordId, code) progress values for every user in [guildId]
     * whose progress on any of [codes] is > 0. Used by the leaderboard
     * "Champions" tab to surface top winners across multiple PvP games
     * with one query.
     */
    fun progressByCodesForGuild(guildId: Long, codes: Collection<String>): List<ProgressByCode>

    data class ProgressByCode(
        val discordId: Long,
        val code: String,
        val progress: Long,
    )

    data class ProgressResult(
        val achievement: AchievementDto?,
        val newProgress: Long,
        val unlocked: Boolean,
        val alreadyUnlocked: Boolean
    )

    data class AchievementView(
        val achievement: AchievementDto,
        val unlockedAt: java.time.Instant?,
        val progress: Long
    )
}
