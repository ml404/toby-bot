package web.service

import database.dto.MessageDailyCountDto
import database.dto.VoiceSessionDto
import database.persistence.MessageDailyCountPersistence
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ActivityChartsServiceTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 23)
    private val clock: Clock = Clock.fixed(
        today.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60 * 60 * 12),
        ZoneOffset.UTC,
    )

    @Test
    fun `messagesPerDay pre-fills every day in the window with zero`() {
        val messages = mockk<MessageDailyCountPersistence> {
            every { findByGuildSince(any(), any()) } returns emptyList()
        }
        val voice = mockk<VoiceSessionService>(relaxed = true)
        val service = ActivityChartsService(messages, voice, clock)

        val series = service.messagesPerDay(guildId = 1L, days = 7)
        assertEquals(7, series.size)
        assertTrue(series.all { it.value == 0.0 })
        assertEquals(today.minusDays(6), series.first().date)
        assertEquals(today, series.last().date)
    }

    @Test
    fun `messagesPerDay maps stored counters onto their dates`() {
        val messages = mockk<MessageDailyCountPersistence> {
            every { findByGuildSince(any(), any()) } returns listOf(
                MessageDailyCountDto(guildId = 1L, dayStart = today, count = 42L),
                MessageDailyCountDto(guildId = 1L, dayStart = today.minusDays(2), count = 7L),
            )
        }
        val voice = mockk<VoiceSessionService>(relaxed = true)
        val service = ActivityChartsService(messages, voice, clock)

        val series = service.messagesPerDay(guildId = 1L, days = 5)
        val byDate = series.associate { it.date to it.value }
        assertEquals(42.0, byDate[today])
        assertEquals(7.0, byDate[today.minusDays(2)])
        assertEquals(0.0, byDate[today.minusDays(4)]) // pre-filled
    }

    @Test
    fun `voiceHoursPerDay sums seconds across the matching calendar day`() {
        val sessionStart = today.atStartOfDay(ZoneOffset.UTC).plusHours(10).toInstant()
        val sessionEnd = sessionStart.plusSeconds(3600) // 1 hour exactly
        val session = closedSession(sessionStart, sessionEnd)
        val service = ActivityChartsService(
            messageDailyCounts = mockk(relaxed = true),
            voiceSessions = mockk { every { findClosedOverlapping(any(), any(), any()) } returns listOf(session) },
            clock = clock,
        )

        val series = service.voiceHoursPerDay(guildId = 1L, days = 7)
        val byDate = series.associate { it.date to it.value }
        assertEquals(1.0, byDate[today], "1 hour entirely on `today`")
        assertEquals(0.0, byDate[today.minusDays(1)])
    }

    @Test
    fun `voiceHoursPerDay splits a session that crosses midnight across two days`() {
        // 23:50 today through 00:10 tomorrow = 10 minutes today + 10 minutes tomorrow.
        // We anchor the "today" of the chart to be one day past the split so both days fall in-window.
        val startInstant = today.minusDays(1).atStartOfDay(ZoneOffset.UTC)
            .plusHours(23).plusMinutes(50).toInstant()
        val endInstant = today.atStartOfDay(ZoneOffset.UTC).plusMinutes(10).toInstant()
        val session = closedSession(startInstant, endInstant)
        val service = ActivityChartsService(
            messageDailyCounts = mockk(relaxed = true),
            voiceSessions = mockk { every { findClosedOverlapping(any(), any(), any()) } returns listOf(session) },
            clock = clock,
        )

        val series = service.voiceHoursPerDay(guildId = 1L, days = 7)
        val byDate = series.associate { it.date to it.value }
        val tenMinutesInHours = 10.0 / 60.0
        assertEquals(tenMinutesInHours, byDate[today.minusDays(1)]!!, 0.001)
        assertEquals(tenMinutesInHours, byDate[today]!!, 0.001)
    }

    @Test
    fun `polylinePoints emits one coord pair per day, scaled to the series max`() {
        val series = listOf(
            ActivityChartsService.DailyPoint(today.minusDays(2), 0.0),
            ActivityChartsService.DailyPoint(today.minusDays(1), 5.0),
            ActivityChartsService.DailyPoint(today, 10.0),
        )
        val out = ActivityChartsService.polylinePoints(series)
        // Three points → three space-separated `x,y` pairs.
        assertEquals(3, out.split(" ").size)
        // Last point's Y sits at the padded top of the chart (highest of the three).
        val lastY = out.split(" ").last().split(",")[1].toDouble()
        val firstY = out.split(" ").first().split(",")[1].toDouble()
        assertTrue(lastY < firstY, "max value should sit higher on the SVG (smaller Y)")
    }

    @Test
    fun `polylinePoints with an empty series returns the empty string`() {
        assertEquals("", ActivityChartsService.polylinePoints(emptyList()))
    }

    @Test
    fun `polylinePoints with all-zero values renders along the baseline`() {
        val series = (0 until 5).map {
            ActivityChartsService.DailyPoint(today.minusDays((4 - it).toLong()), 0.0)
        }
        val out = ActivityChartsService.polylinePoints(series)
        // All Ys should be identical when the series max is zero (clamp to 1.0).
        val ys = out.split(" ").map { it.split(",")[1] }.toSet()
        assertEquals(1, ys.size, "all-zero series should be one horizontal line")
    }

    private fun closedSession(startedAt: Instant, endedAt: Instant): VoiceSessionDto = VoiceSessionDto(
        id = 1L,
        discordId = 99L,
        guildId = 1L,
        channelId = 100L,
        joinedAt = startedAt,
        leftAt = endedAt,
        countedSeconds = endedAt.epochSecond - startedAt.epochSecond,
        creditsAwarded = 0L,
    )
}
