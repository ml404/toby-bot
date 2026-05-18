package database.service.impl

import common.notification.NotificationChannelKind
import database.dto.UserNotificationPrefDto
import database.persistence.UserNotificationPrefPersistence
import database.service.UserNotificationPrefService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultUserNotificationPrefService(
    private val persistence: UserNotificationPrefPersistence
) : UserNotificationPrefService {

    override fun isOptedIn(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind
    ): Boolean {
        val row = persistence.get(discordId, guildId, kind.name)
        return row?.optIn ?: kind.defaultOptIn
    }

    override fun get(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind
    ): UserNotificationPrefDto? = persistence.get(discordId, guildId, kind.name)

    override fun listForUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto> =
        persistence.listByUser(discordId, guildId)

    override fun setPref(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        optIn: Boolean
    ): UserNotificationPrefDto = persistence.upsert(
        UserNotificationPrefDto(
            discordId = discordId,
            guildId = guildId,
            channelKind = kind.name,
            optIn = optIn,
            updatedAt = Instant.now()
        )
    )
}
