package database.persistence.impl

import database.dto.ActivitySessionDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import database.persistence.activity.impl.DefaultActivitySessionPersistence

class DefaultActivitySessionPersistenceTest {

    private lateinit var em: EntityManager
    private lateinit var persistence: DefaultActivitySessionPersistence

    private val guildId = 1L
    private val discordId = 42L

    @BeforeEach
    fun setUp() {
        em = mockk(relaxed = true)
        persistence = DefaultActivitySessionPersistence().apply {
            DefaultActivitySessionPersistence::class.java
                .getDeclaredField("entityManager")
                .apply { isAccessible = true }
                .set(this, em)
        }
    }

    private fun sampleSession(
        id: Long? = null,
        endedAt: Instant? = null,
    ) = ActivitySessionDto(
        id = id,
        discordId = discordId,
        guildId = guildId,
        activityName = "voice",
        startedAt = Instant.parse("2026-05-23T12:00:00Z"),
        endedAt = endedAt,
    )

    @Test
    fun `openSession persists then flushes then returns the same row`() {
        val session = sampleSession()

        val saved = persistence.openSession(session)

        assertSame(session, saved)
        verifyOrder {
            em.persist(session)
            em.flush()
        }
        verify(exactly = 0) { em.merge(any<ActivitySessionDto>()) }
    }

    @Test
    fun `closeSession merges then flushes then returns the same row`() {
        val session = sampleSession(id = 99L, endedAt = Instant.parse("2026-05-23T13:00:00Z"))

        val closed = persistence.closeSession(session)

        assertSame(session, closed)
        verifyOrder {
            em.merge(session)
            em.flush()
        }
        verify(exactly = 0) { em.persist(any<ActivitySessionDto>()) }
    }

    @Test
    fun `findOpen returns the first row of the named query result list`() {
        val session = sampleSession(id = 99L)
        val q = mockNamedQuery("ActivitySessionDto.findOpen", listOf(session))

        assertSame(session, persistence.findOpen(discordId, guildId))
        verify {
            q.setParameter("discordId", discordId)
            q.setParameter("guildId", guildId)
        }
    }

    @Test
    fun `findOpen returns null when the named query returns empty`() {
        mockNamedQuery("ActivitySessionDto.findOpen", emptyList())
        assertNull(persistence.findOpen(discordId, guildId))
    }

    @Test
    fun `findAllOpen returns the named query result list with no parameters bound`() {
        val rows = listOf(sampleSession(id = 1L), sampleSession(id = 2L))
        val q = mockNamedQuery("ActivitySessionDto.findAllOpen", rows)

        assertSame(rows, persistence.findAllOpen())
        verify(exactly = 0) { q.setParameter(any<String>(), any<Any>()) }
    }

    @Test
    fun `findClosedBefore binds the cutoff and returns the rows`() {
        val cutoff = Instant.parse("2026-05-01T00:00:00Z")
        val rows = listOf(sampleSession(id = 1L, endedAt = Instant.parse("2026-04-30T12:00:00Z")))
        val q = mockNamedQuery("ActivitySessionDto.findClosedBefore", rows)

        assertSame(rows, persistence.findClosedBefore(cutoff))
        verify { q.setParameter("cutoff", cutoff) }
    }

    @Test
    fun `deleteClosedBefore executes the named delete query with the cutoff`() {
        val cutoff = Instant.parse("2026-05-01T00:00:00Z")
        val q = mockk<Query>(relaxed = true)
        every { em.createNamedQuery("ActivitySessionDto.deleteClosedBefore") } returns q
        every { q.setParameter("cutoff", cutoff) } returns q
        every { q.executeUpdate() } returns 4

        assertEquals(4, persistence.deleteClosedBefore(cutoff))
        verify { q.setParameter("cutoff", cutoff) }
        verify { q.executeUpdate() }
    }

    private fun mockNamedQuery(
        name: String,
        rows: List<ActivitySessionDto>,
    ): TypedQuery<ActivitySessionDto> {
        val q = mockk<TypedQuery<ActivitySessionDto>>(relaxed = true)
        every {
            em.createNamedQuery(name, ActivitySessionDto::class.java)
        } returns q
        every { q.resultList } returns rows
        return q
    }
}
