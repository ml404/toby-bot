package database.dto.user

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "UserNotificationPrefDto.get",
        query = "select p from UserNotificationPrefDto p " +
                "where p.discordId = :discordId and p.guildId = :guildId " +
                "and p.channelKind = :channelKind and p.surface = :surface"
    ),
    NamedQuery(
        name = "UserNotificationPrefDto.getByUser",
        query = "select p from UserNotificationPrefDto p " +
                "where p.discordId = :discordId and p.guildId = :guildId"
    )
)
@Entity
@Table(name = "user_notification_pref", schema = "public")
@IdClass(UserNotificationPrefId::class)
@Transactional
class UserNotificationPrefDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "channel_kind")
    var channelKind: String = "",

    @Id
    @Column(name = "surface", nullable = false)
    var surface: String = "DM",

    @Column(name = "opt_in", nullable = false)
    var optIn: Boolean = false,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) : Serializable

data class UserNotificationPrefId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var channelKind: String = "",
    var surface: String = "DM",
) : Serializable
