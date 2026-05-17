package database.persistence

import database.dto.LevelRoleRewardDto

interface LevelRoleRewardPersistence {
    fun listForGuild(guildId: Long): List<LevelRoleRewardDto>
    fun get(guildId: Long, level: Int): LevelRoleRewardDto?

    /**
     * Rewards strictly above [fromExclusive] and at or below [toInclusive],
     * ordered ascending by level. Used when a single XP grant crosses
     * multiple level thresholds so every reward in the band gets applied.
     */
    fun listInRange(guildId: Long, fromExclusive: Int, toInclusive: Int): List<LevelRoleRewardDto>

    fun upsert(reward: LevelRoleRewardDto): LevelRoleRewardDto
    fun delete(guildId: Long, level: Int)
}
