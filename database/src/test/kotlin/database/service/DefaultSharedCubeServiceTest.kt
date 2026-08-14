package database.service

import database.dto.user.SharedCubeDto
import database.persistence.user.SharedCubePersistence
import database.service.user.impl.DefaultSharedCubeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DefaultSharedCubeServiceTest {

    private lateinit var persistence: InMemorySharedCubePersistence
    private lateinit var service: DefaultSharedCubeService

    @BeforeEach
    fun setup() {
        persistence = InMemorySharedCubePersistence()
        service = DefaultSharedCubeService(persistence)
    }

    @Test
    fun `create mints a snapshot with a non-blank token and is retrievable`() {
        val at = Instant.parse("2026-06-05T10:00:00Z")
        val row = service.create(100L, "My Cube", "Bolt\nForest", at)

        assertTrue(row.token.isNotBlank())
        assertEquals("My Cube", row.name)
        assertEquals("Bolt\nForest", row.cards)
        assertEquals(100L, row.discordId)
        assertEquals(at, row.createdAt)

        val fetched = service.get(row.token)
        assertEquals("Bolt\nForest", fetched?.cards)
    }

    @Test
    fun `each share gets a distinct token`() {
        val a = service.create(100L, "A", "Bolt")
        val b = service.create(100L, "B", "Shock")
        assertNotEquals(a.token, b.token)
    }

    @Test
    fun `get returns null for an unknown token`() {
        assertNull(service.get("nope"))
    }

    /** In-memory stand-in for the JPA persistence. */
    @Test
    fun `countForUser counts only that user's snapshots`() {
        service.create(100L, "A", "Bolt")
        service.create(100L, "B", "Bolt")
        service.create(200L, "C", "Bolt")

        assertEquals(2L, service.countForUser(100L))
        assertEquals(1L, service.countForUser(200L))
        assertEquals(0L, service.countForUser(300L))
    }

    @Test
    fun `purge removes snapshots created before the cutoff and leaves the rest`() {
        val old = service.create(100L, "Old", "Bolt", Instant.parse("2024-01-01T00:00:00Z"))
        val fresh = service.create(100L, "Fresh", "Bolt", Instant.parse("2026-08-01T00:00:00Z"))

        val deleted = service.purge(Instant.parse("2026-01-01T00:00:00Z"))

        assertEquals(1, deleted)
        assertNull(service.get(old.token)) { "a year-old link is one nobody is coming back to" }
        assertEquals("Fresh", service.get(fresh.token)?.name) { "a recent link must survive the sweep" }
    }

    @Test
    fun `purge is a no-op when nothing is old enough`() {
        service.create(100L, "Fresh", "Bolt", Instant.parse("2026-08-01T00:00:00Z"))

        assertEquals(0, service.purge(Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals(1L, service.countForUser(100L))
    }

    private class InMemorySharedCubePersistence : SharedCubePersistence {
        private val store = mutableMapOf<String, SharedCubeDto>()
        override fun get(token: String): SharedCubeDto? = store[token]
        override fun insert(row: SharedCubeDto): SharedCubeDto {
            store[row.token] = row
            return row
        }

        override fun countForUser(discordId: Long): Long =
            store.values.count { it.discordId == discordId }.toLong()

        override fun deleteOlderThan(cutoff: java.time.Instant): Int {
            val doomed = store.values.filter { it.createdAt < cutoff }.map { it.token }
            doomed.forEach { store.remove(it) }
            return doomed.size
        }
    }
}
