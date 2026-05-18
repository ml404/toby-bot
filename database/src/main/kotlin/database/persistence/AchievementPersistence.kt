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
}
