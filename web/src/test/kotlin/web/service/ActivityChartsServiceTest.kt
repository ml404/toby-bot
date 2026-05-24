package web.service

import database.dto.MessageDailyCountDto
import database.dto.VoiceSessionDto
import database.persistence.MessageDailyCountPersistence
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        val service = serviceWithEmpty()
        val series = listOf(
            ActivityChartsService.DailyPoint(today.minusDays(2), 0.0),
            ActivityChartsService.DailyPoint(today.minusDays(1), 5.0),
            ActivityChartsService.DailyPoint(today, 10.0),
        )
        val out = service.polylinePoints(series)
        // Three points → three space-separated `x,y` pairs.
        assertEquals(3, out.split(" ").size)
        // Last point's Y sits at the padded top of the chart (highest of the three).
        val lastY = out.split(" ").last().split(",")[1].toDouble()
        val firstY = out.split(" ").first().split(",")[1].toDouble()
        assertTrue(lastY < firstY, "max value should sit higher on the SVG (smaller Y)")
    }

    @Test
    fun `polylinePoints with an empty series returns the empty string`() {
        assertEquals("", serviceWithEmpty().polylinePoints(emptyList()))
    }

    @Test
    fun `polylinePoints with all-zero values renders along the baseline`() {
        val service = serviceWithEmpty()
        val series = (0 until 5).map {
            ActivityChartsService.DailyPoint(today.minusDays((4 - it).toLong()), 0.0)
        }
        val out = service.polylinePoints(series)
        // All Ys should be identical when the series max is zero (clamp to 1.0).
        val ys = out.split(" ").map { it.split(",")[1] }.toSet()
        assertEquals(1, ys.size, "all-zero series should be one horizontal line")
    }

    // ---- ChartView builders ----

    @Test
    fun `buildMessagesChart returns a fully-populated ChartView bundle`() {
        val messages = mockk<MessageDailyCountPersistence> {
            every { findByGuildSince(any(), any()) } returns listOf(
                MessageDailyCountDto(guildId = 1L, dayStart = today, count = 12L),
                MessageDailyCountDto(guildId = 1L, dayStart = today.minusDays(1), count = 8L),
            )
        }
        val service = ActivityChartsService(messages, mockk(relaxed = true), clock)

        val chart = service.buildMessagesChart(guildId = 1L, days = 7)
        assertEquals(7, chart.points.size)
        assertEquals(20.0, chart.total)
        assertEquals(20L, chart.totalRounded)
        assertEquals(12.0, chart.max)
        assertEquals(12L, chart.maxRounded)
        assertEquals(ActivityChartsService.CHART_WIDTH, chart.viewBoxWidth)
        assertEquals(ActivityChartsService.CHART_HEIGHT, chart.viewBoxHeight)
        assertEquals(today.minusDays(6), chart.firstDate)
        assertEquals(today, chart.lastDate)
        assertTrue(chart.polyline.isNotEmpty(), "polyline must be ready for the template")
        assertFalse(chart.isEmpty)
    }

    @Test
    fun `ChartView pre-computes markers, area polygon, gridlines, axis dates, and average`() {
        val messages = mockk<MessageDailyCountPersistence> {
            every { findByGuildSince(any(), any()) } returns listOf(
                MessageDailyCountDto(guildId = 1L, dayStart = today, count = 10L),
                MessageDailyCountDto(guildId = 1L, dayStart = today.minusDays(2), count = 6L),
            )
        }
        val service = ActivityChartsService(messages, mockk(relaxed = true), clock)

        val chart = service.buildMessagesChart(guildId = 1L, days = 5)

        // One marker per data point, in series order.
        assertEquals(chart.points.size, chart.markers.size)
        assertEquals(today.minusDays(4), chart.markers.first().date)
        assertEquals(today, chart.markers.last().date)
        // Marker on the peak day sits higher (smaller Y) than the zero days.
        val peakMarker = chart.markers.first { it.date == today }
        val zeroMarker = chart.markers.first { it.value == 0.0 }
        assertTrue(peakMarker.y < zeroMarker.y, "peak marker should sit above zero marker")

        // Area polygon = polyline points + two closing corners along the baseline.
        val polylineCount = chart.polyline.split(" ").size
        val areaCount = chart.areaPolygon.split(" ").size
        assertEquals(polylineCount + 2, areaCount, "area polygon should close back to the baseline")

        // Three gridlines (top/mid/baseline), ordered top→bottom in SVG units (ascending Y).
        assertEquals(3, chart.gridLines.size)
        val ys = chart.gridLines.map { it.y }
        assertEquals(ys.sorted(), ys, "gridlines should be ordered top → baseline")
        assertEquals(0.0, chart.gridLines.last().value)
        assertEquals(chart.max, chart.gridLines.first().value)

        // Axis dates: first / middle / last.
        assertEquals(listOf(today.minusDays(4), today.minusDays(2), today), chart.axisDates)

        // Averages and active-day count match the series.
        assertEquals(16.0 / 5.0, chart.dailyAverage, 0.0001)
        assertEquals(2, chart.nonZeroDays)
    }

    @Test
    fun `ChartView treats an all-zero series as empty for layout purposes`() {
        val messages = mockk<MessageDailyCountPersistence> {
            every { findByGuildSince(any(), any()) } returns emptyList()
        }
        val service = ActivityChartsService(messages, mockk(relaxed = true), clock)

        val chart = service.buildMessagesChart(guildId = 1L, days = 5)
        assertTrue(chart.isEmpty, "zero-only series should render the empty-state card")
        assertEquals(0, chart.nonZeroDays)
        assertEquals(0.0, chart.dailyAverage)
    }

    @Test
    fun `buildVoiceHoursChart returns a fully-populated ChartView bundle`() {
        val sessionStart = today.atStartOfDay(ZoneOffset.UTC).plusHours(10).toInstant()
        val sessionEnd = sessionStart.plusSeconds(3600) // 1 hour exactly
        val voice = mockk<VoiceSessionService> {
            every { findClosedOverlapping(any(), any(), any()) } returns listOf(closedSession(sessionStart, sessionEnd))
        }
        val service = ActivityChartsService(mockk(relaxed = true), voice, clock)

        val chart = service.buildVoiceHoursChart(guildId = 1L, days = 7)
        assertEquals(7, chart.points.size)
        assertEquals(1.0, chart.total, 0.001)
        assertEquals(1.0, chart.max, 0.001)
        assertEquals(today.minusDays(6), chart.firstDate)
        assertEquals(today, chart.lastDate)
        assertTrue(chart.polyline.isNotEmpty())
    }

    private fun serviceWithEmpty(): ActivityChartsService = ActivityChartsService(
        messageDailyCounts = mockk(relaxed = true),
        voiceSessions = mockk(relaxed = true),
        clock = clock,
    )

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
