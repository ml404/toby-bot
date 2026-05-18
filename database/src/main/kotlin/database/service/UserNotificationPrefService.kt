package database.service

import common.notification.NotificationChannelKind
import database.dto.UserNotificationPrefDto

interface UserNotificationPrefService {
    /**
     * Resolve whether [discordId] in [guildId] wants to receive
     * notifications of [kind]. Falls back to [NotificationChannelKind.defaultOptIn]
     * when the user has no row for that kind.
     */
    fun isOptedIn(discordId: Long, guildId: Long, kind: NotificationChannelKind): Boolean

    fun get(discordId: Long, guildId: Long, kind: NotificationChannelKind): UserNotificationPrefDto?

    fun listForUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto>

    fun setPref(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        optIn: Boolean
    ): UserNotificationPrefDto
}
