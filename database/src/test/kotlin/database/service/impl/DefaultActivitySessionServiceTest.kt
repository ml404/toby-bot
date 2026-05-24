package database.service.impl

import database.dto.ActivitySessionDto
import database.persistence.ActivitySessionPersistence
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import database.service.activity.impl.DefaultActivitySessionService

class DefaultActivitySessionServiceTest {

    private lateinit var persistence: ActivitySessionPersistence
    private lateinit var service: DefaultActivitySessionService

    private val guildId = 1L
    private val discordId = 42L

    @BeforeEach
    fun setUp() {
        persistence = mockk(relaxed = false)
        service = DefaultActivitySessionService(persistence)
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
    fun `openSession delegates and returns the stored row`() {
        val incoming = sampleSession()
        val stored = sampleSession(id = 99L)
        every { persistence.openSession(incoming) } returns stored

        assertSame(stored, service.openSession(incoming))
        verify(exactly = 1) { persistence.openSession(incoming) }
    }

    @Test
    fun `closeSession delegates the same row reference and returns the merged row`() {
        val session = sampleSession(id = 99L, endedAt = Instant.parse("2026-05-23T13:00:00Z"))
        every { persistence.closeSession(session) } returns session

        assertSame(session, service.closeSession(session))
        verify(exactly = 1) { persistence.closeSession(session) }
    }

    @Test
    fun `findOpen returns the persistence result and passes both keys through`() {
        val session = sampleSession(id = 99L)
        every { persistence.findOpen(discordId, guildId) } returns session

        assertSame(session, service.findOpen(discordId, guildId))
        verify(exactly = 1) { persistence.findOpen(discordId, guildId) }
    }

    @Test
    fun `findOpen returns null when no open session exists`() {
        every { persistence.findOpen(discordId, guildId) } returns null

        assertNull(service.findOpen(discordId, guildId))
        verify(exactly = 1) { persistence.findOpen(discordId, guildId) }
    }

    @Test
    fun `findAllOpen returns the persistence list`() {
        val rows = listOf(sampleSession(id = 1L), sampleSession(id = 2L))
        every { persistence.findAllOpen() } returns rows

        assertEquals(rows, service.findAllOpen())
        verify(exactly = 1) { persistence.findAllOpen() }
    }

    @Test
    fun `findClosedBefore passes the cutoff through and returns the persistence list`() {
        val cutoff = Instant.parse("2026-05-01T00:00:00Z")
        val rows = listOf(sampleSession(id = 1L, endedAt = Instant.parse("2026-04-30T12:00:00Z")))
        every { persistence.findClosedBefore(cutoff) } returns rows

        assertEquals(rows, service.findClosedBefore(cutoff))
        verify(exactly = 1) { persistence.findClosedBefore(cutoff) }
    }

    @Test
    fun `deleteClosedBefore passes the cutoff and returns the affected-row count`() {
        val cutoff = Instant.parse("2026-05-01T00:00:00Z")
        every { persistence.deleteClosedBefore(cutoff) } returns 12

        assertEquals(12, service.deleteClosedBefore(cutoff))
        verify(exactly = 1) { persistence.deleteClosedBefore(cutoff) }
    }
}
