package database.service

import database.dto.user.CubeListDto
import database.persistence.user.CubeListPersistence
import database.service.user.impl.DefaultCubeListService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DefaultCubeListServiceTest {

    private val discordId = 100L

    private lateinit var persistence: InMemoryCubeListPersistence
    private lateinit var service: DefaultCubeListService

    @BeforeEach
    fun setup() {
        persistence = InMemoryCubeListPersistence()
        service = DefaultCubeListService(persistence)
    }

    @Test
    fun `save persists a new list and listForUser returns it`() {
        val at = Instant.parse("2026-06-05T10:00:00Z")
        val row = service.save(discordId, "My Cube", "Lightning Bolt\nForest", at)
        assertEquals("My Cube", row.name)
        assertEquals("Lightning Bolt\nForest", row.cards)
        assertEquals(at, row.createdAt)
        assertEquals(at, row.updatedAt)

        val list = service.listForUser(discordId)
        assertEquals(1, list.size)
        assertEquals("My Cube", list[0].name)
    }

    @Test
    fun `re-saving the same name overwrites cards and bumps updatedAt but keeps createdAt`() {
        val first = Instant.parse("2026-06-05T10:00:00Z")
        val second = Instant.parse("2026-06-06T10:00:00Z")
        service.save(discordId, "My Cube", "Bolt", first)
        val updated = service.save(discordId, "My Cube", "Bolt\nShock", second)

        assertEquals("Bolt\nShock", updated.cards)
        assertEquals(first, updated.createdAt, "createdAt should be preserved")
        assertEquals(second, updated.updatedAt)
        // Still a single list under that name.
        assertEquals(1, service.listForUser(discordId).size)
    }

    @Test
    fun `lists are scoped per user`() {
        service.save(discordId, "Mine", "Bolt")
        service.save(200L, "Theirs", "Shock")
        assertEquals(listOf("Mine"), service.listForUser(discordId).map { it.name })
        assertEquals(listOf("Theirs"), service.listForUser(200L).map { it.name })
    }

    @Test
    fun `get returns a saved list or null`() {
        service.save(discordId, "My Cube", "Bolt")
        assertEquals("Bolt", service.get(discordId, "My Cube")?.cards)
        assertNull(service.get(discordId, "Nope"))
        assertNull(service.get(999L, "My Cube"))
    }

    @Test
    fun `delete removes the list and reports whether anything was removed`() {
        service.save(discordId, "My Cube", "Bolt")
        assertTrue(service.delete(discordId, "My Cube"))
        assertFalse(service.delete(discordId, "My Cube")) // already gone
        assertTrue(service.listForUser(discordId).isEmpty())
    }

    /** Minimal in-memory stand-in for the JPA-backed persistence. */
    private class InMemoryCubeListPersistence : CubeListPersistence {
        private val store = linkedMapOf<Pair<Long, String>, CubeListDto>()

        override fun listForUser(discordId: Long): List<CubeListDto> =
            store.values.filter { it.discordId == discordId }.sortedBy { it.name }

        override fun get(discordId: Long, name: String): CubeListDto? = store[discordId to name]

        override fun upsert(row: CubeListDto): CubeListDto {
            val key = row.discordId to row.name
            val existing = store[key]
            return if (existing == null) {
                store[key] = row
                row
            } else {
                existing.cards = row.cards
                existing.updatedAt = row.updatedAt
                existing
            }
        }

        override fun delete(discordId: Long, name: String): Int =
            if (store.remove(discordId to name) != null) 1 else 0
    }
}
