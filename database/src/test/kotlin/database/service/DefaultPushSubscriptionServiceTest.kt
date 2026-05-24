package database.service

import database.dto.PushSubscriptionDto
import database.persistence.PushSubscriptionPersistence
import database.service.user.impl.DefaultPushSubscriptionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DefaultPushSubscriptionServiceTest {

    private val discordId = 100L
    private val endpoint = "https://fcm.googleapis.com/fcm/send/abc123"

    private lateinit var persistence: InMemoryPushSubscriptionPersistence
    private lateinit var service: DefaultPushSubscriptionService

    @BeforeEach
    fun setup() {
        persistence = InMemoryPushSubscriptionPersistence()
        service = DefaultPushSubscriptionService(persistence)
    }

    @Test
    fun `subscribe persists a new row and returns it`() {
        val now = Instant.parse("2026-05-19T10:00:00Z")
        val row = service.subscribe(
            discordId = discordId,
            endpoint = endpoint,
            p256dh = "pk1",
            auth = "auth1",
            userAgent = "Firefox/119.0",
            at = now,
        )
        assertEquals(endpoint, row.endpoint)
        assertEquals(discordId, row.discordId)
        assertEquals("pk1", row.p256dh)
        assertEquals("auth1", row.auth)
        assertEquals("Firefox/119.0", row.userAgent)
        assertEquals(now, row.createdAt)
        assertNull(row.lastUsedAt)

        // listForUser returns it.
        val list = service.listForUser(discordId)
        assertEquals(1, list.size)
        assertEquals(endpoint, list[0].endpoint)
    }

    @Test
    fun `subscribe again with same endpoint refreshes keys and owner but preserves createdAt`() {
        val firstAt = Instant.parse("2026-05-19T10:00:00Z")
        val secondAt = Instant.parse("2026-05-19T12:00:00Z")
        service.subscribe(discordId, endpoint, "pk1", "auth1", "Firefox", firstAt)

        // Same endpoint, different keys, different owner — handles shared
        // devices and browser key rotation.
        val other = 200L
        val refreshed = service.subscribe(other, endpoint, "pk2", "auth2", "Chrome", secondAt)

        assertEquals(other, refreshed.discordId, "endpoint re-anchors to the most recent caller")
        assertEquals("pk2", refreshed.p256dh)
        assertEquals("auth2", refreshed.auth)
        assertEquals("Chrome", refreshed.userAgent)
        assertEquals(firstAt, refreshed.createdAt, "createdAt is preserved on refresh")

        // listForUser should reflect ownership change.
        assertTrue(service.listForUser(discordId).isEmpty())
        assertEquals(1, service.listForUser(other).size)
    }

    @Test
    fun `unsubscribe removes the row and is idempotent`() {
        service.subscribe(discordId, endpoint, "pk", "a", null, Instant.now())
        assertTrue(service.unsubscribe(endpoint))
        assertNull(service.get(endpoint))
        // Second call → false (nothing to remove).
        assertFalse(service.unsubscribe(endpoint))
    }

    @Test
    fun `listForUser returns multiple subscriptions for the same user`() {
        service.subscribe(discordId, "$endpoint/laptop", "p1", "a1", "Firefox/Linux", Instant.now())
        service.subscribe(discordId, "$endpoint/phone", "p2", "a2", "Chrome/Android", Instant.now())
        assertEquals(2, service.listForUser(discordId).size)
    }

    @Test
    fun `listForUser scopes by user`() {
        service.subscribe(discordId, "$endpoint/a", "p", "a", null, Instant.now())
        service.subscribe(999L, "$endpoint/b", "p", "a", null, Instant.now())
        assertEquals(1, service.listForUser(discordId).size)
        assertEquals(1, service.listForUser(999L).size)
    }

    @Test
    fun `markUsed updates the watermark on the row`() {
        service.subscribe(discordId, endpoint, "pk", "a", null, Instant.parse("2026-05-19T10:00:00Z"))
        val when_ = Instant.parse("2026-05-19T11:30:00Z")
        service.markUsed(endpoint, when_)
        val row = service.get(endpoint)
        assertNotNull(row)
        assertEquals(when_, row!!.lastUsedAt)
    }

    @Test
    fun `markUsed for an unknown endpoint is silent`() {
        // No throw, no insertion.
        service.markUsed("https://does.not/exist", Instant.now())
        assertEquals(0, service.listForUser(discordId).size)
    }

    @Test
    fun `get returns null for an unknown endpoint`() {
        assertNull(service.get("https://nope"))
    }

    private class InMemoryPushSubscriptionPersistence : PushSubscriptionPersistence {
        private val rows = mutableMapOf<String, PushSubscriptionDto>()

        override fun get(endpoint: String): PushSubscriptionDto? = rows[endpoint]

        override fun listForUser(discordId: Long): List<PushSubscriptionDto> =
            rows.values.filter { it.discordId == discordId }

        override fun upsert(row: PushSubscriptionDto): PushSubscriptionDto {
            rows[row.endpoint] = row
            return row
        }

        override fun delete(endpoint: String): Int {
            val had = rows.remove(endpoint) != null
            return if (had) 1 else 0
        }
    }
}
