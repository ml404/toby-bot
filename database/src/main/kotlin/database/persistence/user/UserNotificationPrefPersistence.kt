package database.persistence.user

import database.dto.UserNotificationPrefDto

interface UserNotificationPrefPersistence {
    fun get(
        discordId: Long,
        guildId: Long,
        channelKind: String,
        surface: String,
    ): UserNotificationPrefDto?

    fun listByUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto>

    /**
     * Insert when no row matches the 4-column PK
     * `(discord_id, guild_id, channel_kind, surface)`; update otherwise.
     * The same `(kind, DM)` and `(kind, CHANNEL)` rows are distinct.
     */
    fun upsert(row: UserNotificationPrefDto): UserNotificationPrefDto
}
