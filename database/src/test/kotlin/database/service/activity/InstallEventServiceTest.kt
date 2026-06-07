package database.service.activity

import database.dto.activity.InstallEventType
import database.persistence.activity.InstallEventPersistence
import database.service.activity.impl.DefaultInstallEventService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class InstallEventServiceTest {

    private val persistence: InstallEventPersistence = mockk(relaxed = true)
    private val service = DefaultInstallEventService(persistence)

    @Test
    fun `recordJoin appends a JOIN at the supplied instant`() {
        val at = Instant.parse("2026-01-02T03:04:05Z")
        service.recordJoin(42L, at)
        verify(exactly = 1) { persistence.record(42L, InstallEventType.JOIN, at) }
    }

    @Test
    fun `recordLeave appends a LEAVE at the supplied instant`() {
        val at = Instant.parse("2026-02-03T04:05:06Z")
        service.recordLeave(99L, at)
        verify(exactly = 1) { persistence.record(99L, InstallEventType.LEAVE, at) }
    }

    @Test
    fun `count delegates to persistence by type`() {
        every { persistence.countByType(InstallEventType.JOIN) } returns 7L
        assertEquals(7L, service.countByType(InstallEventType.JOIN))
    }

    @Test
    fun `countSince delegates to persistence by type and cutoff`() {
        val since = Instant.parse("2026-01-01T00:00:00Z")
        every { persistence.countByTypeSince(InstallEventType.LEAVE, since) } returns 3L
        assertEquals(3L, service.countByTypeSince(InstallEventType.LEAVE, since))
    }
}
