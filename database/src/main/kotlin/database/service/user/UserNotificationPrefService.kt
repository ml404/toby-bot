package database.service.user

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.UserNotificationPrefDto

interface UserNotificationPrefService {
    /**
     * Whether [discordId] in [guildId] wants to receive notifications of
     * [kind] via [surface]. Falls back to [NotificationChannelKind.defaultOptIn]
     * when no explicit row exists. Defensive: returns `false` when [kind]
     * doesn't support [surface] so a misconfigured caller can't accidentally
     * dispatch via an unsupported surface.
     */
    fun isOptedIn(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        surface: Surface,
    ): Boolean

    fun get(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        surface: Surface,
    ): UserNotificationPrefDto?

    fun listForUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto>

    /**
     * Persist the user's opt-in/out for [kind] on [surface]. Rejects
     * (with [IllegalArgumentException]) when [kind] doesn't support
     * [surface] — callers (`/notify` command, REST API) translate this
     * to a user-facing error message.
     */
    fun setPref(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        surface: Surface,
        optIn: Boolean,
    ): UserNotificationPrefDto
}
