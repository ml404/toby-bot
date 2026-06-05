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
    private class InMemorySharedCubePersistence : SharedCubePersistence {
        private val store = mutableMapOf<String, SharedCubeDto>()
        override fun get(token: String): SharedCubeDto? = store[token]
        override fun insert(row: SharedCubeDto): SharedCubeDto {
            store[row.token] = row
            return row
        }
    }
}
