package database.service.user

import database.dto.PushSubscriptionDto
import java.time.Instant

interface PushSubscriptionService {
    /**
     * Register a browser-issued web-push subscription against [discordId].
     * Idempotent on [endpoint]: a re-subscribe from the same device just
     * refreshes the keys and the owning user (handles shared-device scenarios).
     */
    fun subscribe(
        discordId: Long,
        endpoint: String,
        p256dh: String,
        auth: String,
        userAgent: String?,
        at: Instant = Instant.now(),
    ): PushSubscriptionDto

    /** Idempotent — true if a row was actually removed. */
    fun unsubscribe(endpoint: String): Boolean

    fun listForUser(discordId: Long): List<PushSubscriptionDto>

    fun get(endpoint: String): PushSubscriptionDto?

    /** Bumps the last_used_at watermark so the UI can show "last delivered" age. */
    fun markUsed(endpoint: String, at: Instant = Instant.now())
}
