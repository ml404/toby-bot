package database.persistence

import database.dto.AchievementDto
import database.dto.AchievementProgressDto
import database.dto.UserAchievementDto

interface AchievementPersistence {
    fun listAll(): List<AchievementDto>
    fun getByCode(code: String): AchievementDto?
    fun getById(id: Long): AchievementDto?
    fun save(achievement: AchievementDto): AchievementDto

    fun listOwnedByUser(discordId: Long, guildId: Long): List<UserAchievementDto>
    fun owns(discordId: Long, guildId: Long, achievementId: Long): Boolean
    fun recordUnlock(unlock: UserAchievementDto): UserAchievementDto

    fun getProgress(discordId: Long, guildId: Long, achievementId: Long): AchievementProgressDto?
    fun listProgressByUser(discordId: Long, guildId: Long): List<AchievementProgressDto>
    fun upsertProgress(row: AchievementProgressDto): AchievementProgressDto

    /**
     * Per-(discordId, code) progress values for every user in [guildId] whose
     * progress on any of [codes] is > 0. Returned rows are flat
     * `(discordId, code, progress)` triples — the caller aggregates as it
     * sees fit (e.g. total-wins-across-games for the leaderboard).
     *
     * Used by the leaderboard "Champions" tab to surface top PvP winners
     * without N round-trips through [getProgress].
     */
    fun progressByCodesForGuild(guildId: Long, codes: Collection<String>): List<ProgressByCodeRow>
}

/** Flat projection used by [AchievementPersistence.progressByCodesForGuild]. */
data class ProgressByCodeRow(
    val discordId: Long,
    val code: String,
    val progress: Long,
)
