package database.service.user.impl

import database.dto.user.PushSubscriptionDto
import database.persistence.user.PushSubscriptionPersistence
import database.service.user.PushSubscriptionService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultPushSubscriptionService(
    private val persistence: PushSubscriptionPersistence,
) : PushSubscriptionService {

    override fun subscribe(
        discordId: Long,
        endpoint: String,
        p256dh: String,
        auth: String,
        userAgent: String?,
        at: Instant,
    ): PushSubscriptionDto {
        val existing = persistence.get(endpoint)
        return persistence.upsert(
            PushSubscriptionDto(
                endpoint = endpoint,
                discordId = discordId,
                p256dh = p256dh,
                auth = auth,
                userAgent = userAgent,
                createdAt = existing?.createdAt ?: at,
                lastUsedAt = existing?.lastUsedAt,
            )
        )
    }

    override fun unsubscribe(endpoint: String): Boolean = persistence.delete(endpoint) > 0

    override fun listForUser(discordId: Long): List<PushSubscriptionDto> =
        persistence.listForUser(discordId)

    override fun get(endpoint: String): PushSubscriptionDto? = persistence.get(endpoint)

    override fun markUsed(endpoint: String, at: Instant) {
        val row = persistence.get(endpoint) ?: return
        row.lastUsedAt = at
        persistence.upsert(row)
    }
}
