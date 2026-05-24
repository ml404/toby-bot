package database.service.leveling

import database.dto.leveling.LevelRoleRewardDto

interface LevelRoleRewardService {
    fun listForGuild(guildId: Long): List<LevelRoleRewardDto>
    fun get(guildId: Long, level: Int): LevelRoleRewardDto?
    fun listInRange(guildId: Long, fromExclusive: Int, toInclusive: Int): List<LevelRoleRewardDto>
    fun upsert(reward: LevelRoleRewardDto): LevelRoleRewardDto
    fun delete(guildId: Long, level: Int)
}
