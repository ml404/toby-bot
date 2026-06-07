package web.service

import database.dto.activity.InstallEventDto
import database.dto.activity.InstallEventType
import database.service.activity.InstallEventService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InstallChartsServiceTest {

    private val now = Instant.parse("2026-06-15T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private lateinit var installEventService: InstallEventService
    private lateinit var service: InstallChartsService

    @BeforeEach
    fun setup() {
        installEventService = mockk(relaxed = true)
        service = InstallChartsService(installEventService, clock)
    }

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun row(installedAt: Long?): AdminInstallsService.InstallRow =
        AdminInstallsService.InstallRow(
            guildId = "g", guildName = "G", iconUrl = null, ownerId = "1", ownerName = null,
            memberCount = 0, installMode = "express", installedAtMillis = installedAt,
            botJoinedAtMillis = null, serverCreatedMillis = null, boostTier = 0, boostCount = 0,
            locale = null, channelCount = 0, roleCount = 0, features = emptyList(),
            daysSinceInstall = null, serverAgeDays = null,
            healthIssues = emptyList(), lastActiveMillis = null, isDormant = false,
        )

    private fun leave(iso: String) =
        InstallEventDto(guildId = 1L, eventType = InstallEventType.LEAVE.name, occurredAt = Instant.parse(iso))

    @Test
    fun `buckets installs by month and removals from the ledger`() {
        val rows = listOf(
            row(millis("2026-06-01T00:00:00Z")),
            row(millis("2026-06-20T00:00:00Z")),
            row(millis("2026-05-10T00:00:00Z")),
            row(null), // legacy, no date — excluded
        )
        every { installEventService.findSince(any()) } returns listOf(
            leave("2026-06-05T00:00:00Z"),
            // A JOIN row in the ledger must not be counted as a removal.
            InstallEventDto(guildId = 2L, eventType = InstallEventType.JOIN.name, occurredAt = Instant.parse("2026-06-06T00:00:00Z")),
        )

        val view = service.build(rows)

        assertEquals(12, view.buckets.size)
        val june = view.buckets.last()
        assertEquals("Jun 2026", june.label)
        assertEquals(2, june.installs)
        assertEquals(1, june.removals)
        assertEquals(1, june.net)
        val may = view.buckets[view.buckets.size - 2]
        assertEquals(1, may.installs)
        assertEquals(0, may.removals)
        assertEquals(3, view.totalInstalls)
        assertEquals(1, view.totalRemovals)
        assertEquals(2, view.netTotal)
        assertFalse(view.isEmpty)
    }

    @Test
    fun `bar heights are scaled to the busiest month`() {
        // June: 2 installs (the max anywhere) → 100%. May: 1 install → 50%.
        val rows = listOf(
            row(millis("2026-06-01T00:00:00Z")),
            row(millis("2026-06-02T00:00:00Z")),
            row(millis("2026-05-02T00:00:00Z")),
        )
        every { installEventService.findSince(any()) } returns emptyList()

        val view = service.build(rows)

        assertEquals(2, view.maxValue)
        assertEquals(100, view.buckets.last().installsHeightPct)
        assertEquals(50, view.buckets[view.buckets.size - 2].installsHeightPct)
    }

    @Test
    fun `installs outside the trailing window are dropped`() {
        // 2 years ago — well outside the 12-month window.
        val rows = listOf(row(millis("2024-01-01T00:00:00Z")))
        every { installEventService.findSince(any()) } returns emptyList()

        val view = service.build(rows)

        assertEquals(0, view.totalInstalls)
        assertTrue(view.isEmpty)
    }

    @Test
    fun `empty inputs produce an empty chart`() {
        every { installEventService.findSince(any()) } returns emptyList()

        val view = service.build(emptyList())

        assertTrue(view.isEmpty)
        assertEquals(0, view.maxValue)
        assertEquals(12, view.buckets.size) // window still rendered, just all-zero
    }
}
