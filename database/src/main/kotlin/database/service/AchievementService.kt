package database.service

import database.dto.AchievementDto

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
     * Snapshot for the user's profile / `/achievements` view. Includes
     * locked entries (unless hidden) so the user sees what's available.
     */
    fun listFor(discordId: Long, guildId: Long): List<AchievementView>

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
