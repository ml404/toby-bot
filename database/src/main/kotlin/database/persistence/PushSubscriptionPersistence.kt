package database.persistence

import database.dto.PushSubscriptionDto

interface PushSubscriptionPersistence {
    fun get(endpoint: String): PushSubscriptionDto?

    fun listForUser(discordId: Long): List<PushSubscriptionDto>

    /**
     * Insert when no row matches the [PushSubscriptionDto.endpoint] PK; update
     * the discord_id, keys, user_agent, and last_used_at otherwise. A browser
     * can rotate its `auth`/`p256dh` keys while keeping the same endpoint URL,
     * and a shared device can re-subscribe under a different Discord account —
     * upsert handles both.
     */
    fun upsert(row: PushSubscriptionDto): PushSubscriptionDto

    /** Idempotent — returns the number of rows actually removed. */
    fun delete(endpoint: String): Int
}
