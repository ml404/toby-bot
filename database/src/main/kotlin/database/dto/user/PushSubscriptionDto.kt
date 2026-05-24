package database.dto.user

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "PushSubscriptionDto.get",
        query = "select s from PushSubscriptionDto s where s.endpoint = :endpoint"
    ),
    NamedQuery(
        name = "PushSubscriptionDto.listForUser",
        query = "select s from PushSubscriptionDto s where s.discordId = :discordId"
    ),
    NamedQuery(
        name = "PushSubscriptionDto.deleteByEndpoint",
        query = "delete from PushSubscriptionDto s where s.endpoint = :endpoint"
    )
)
@Entity
@Table(name = "push_subscription", schema = "public")
@Transactional
class PushSubscriptionDto(
    @Id
    @Column(name = "endpoint")
    var endpoint: String = "",

    @Column(name = "discord_id", nullable = false)
    var discordId: Long = 0,

    @Column(name = "p256dh", nullable = false)
    var p256dh: String = "",

    @Column(name = "auth", nullable = false)
    var auth: String = "",

    @Column(name = "user_agent")
    var userAgent: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
) : Serializable
