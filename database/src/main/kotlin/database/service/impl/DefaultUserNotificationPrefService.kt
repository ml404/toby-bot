package database.service.impl

import common.notification.NotificationChannelKind
import common.notification.Surface
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
        kind: NotificationChannelKind,
        surface: Surface,
    ): Boolean {
        if (!kind.supports(surface)) return false
        val row = persistence.get(discordId, guildId, kind.name, surface.name)
        return row?.optIn ?: kind.defaultOptIn(surface)
    }

    override fun get(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        surface: Surface,
    ): UserNotificationPrefDto? = persistence.get(discordId, guildId, kind.name, surface.name)

    override fun listForUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto> =
        persistence.listByUser(discordId, guildId)

    override fun setPref(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        surface: Surface,
        optIn: Boolean,
    ): UserNotificationPrefDto {
        require(kind.supports(surface)) {
            "Notification kind ${kind.name} does not support surface ${surface.name}"
        }
        return persistence.upsert(
            UserNotificationPrefDto(
                discordId = discordId,
                guildId = guildId,
                channelKind = kind.name,
                surface = surface.name,
                optIn = optIn,
                updatedAt = Instant.now()
            )
        )
    }
}
