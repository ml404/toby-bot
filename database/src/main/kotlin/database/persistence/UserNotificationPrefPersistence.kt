package database.persistence

import database.dto.UserNotificationPrefDto

interface UserNotificationPrefPersistence {
    fun get(discordId: Long, guildId: Long, channelKind: String): UserNotificationPrefDto?
    fun listByUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto>
    fun upsert(row: UserNotificationPrefDto): UserNotificationPrefDto
}
