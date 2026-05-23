package web.service

import database.persistence.MessageDailyCountPersistence
import database.service.VoiceSessionService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Reads the daily activity series feeding the moderation Activity tab.
 * Two charts, both 30-day-rolling, both bucketed by UTC date:
 *
 *  - [messagesPerDay] reads the dedicated `message_daily_count` counter
 *    (filled by [database.service.MessageActivityBuffer] in batches off
 *    the JDA dispatch thread)
 *  - [voiceHoursPerDay] aggregates closed voice sessions at query time;
 *    sessions that straddle midnight UTC are split proportionally across
 *    both days they touched so the rendered line reflects actual presence
 *
 * Both methods pre-fill empty days with zeros so the template never has
 * to worry about gaps in the X axis.
 *
 * The page renders via [ChartView] — a render-ready bundle of pre-
 * computed polyline + headline numbers + date range — so the template
 * never has to call helper functions through Thymeleaf SpEL. See
 * [buildMessagesChart] and [buildVoiceHoursChart] for the controller-
 * facing entry points.
 */
@Service
class ActivityChartsService(
    private val messageDailyCounts: MessageDailyCountPersistence,
    private val voiceSessions: VoiceSessionService,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class DailyPoint(
        val date: LocalDate,
        val value: Double,
    ) {
        /** The chart template renders both metrics through a single SVG fragment. */
        val valueLong: Long get() = value.toLong()
    }

    /**
     * Render-ready bundle for one chart. Everything the template needs
     * is pre-computed in the service so the Thymeleaf page only has to
     * read `${chart.field}` — no SpEL static-method calls, no `T(...)`
     * type references. Matches the moderation-page convention used by
     * `leveling.html`, `lottery.html`, etc.
     */
    data class ChartView(
        val points: List<DailyPoint>,
        /** Pre-rendered SVG `points="..."` value for `<polyline>`. */
        val polyline: String,
        val total: Double,
        val max: Double,
        val viewBoxWidth: Int,
        val viewBoxHeight: Int,
        val firstDate: LocalDate?,
        val lastDate: LocalDate?,
    ) {
        /** Display-only int variants for headline strong-tags. */
        val totalRounded: Long get() = round(total).toLong()
        val maxRounded: Long get() = round(max).toLong()
        val isEmpty: Boolean get() = points.isEmpty()
    }

    /** Build the messages-per-day chart for [guildId]. */
    fun buildMessagesChart(guildId: Long, days: Int = DEFAULT_DAYS): ChartView =
        toChartView(messagesPerDay(guildId, days))

    /** Build the voice-hours-per-day chart for [guildId]. */
    fun buildVoiceHoursChart(guildId: Long, days: Int = DEFAULT_DAYS): ChartView =
        toChartView(voiceHoursPerDay(guildId, days))

    /**
     * Messages-per-day for [guildId] over the trailing [days] calendar
     * dates (UTC), ascending. Missing days are pre-filled with 0 so
     * the polyline has one point per day regardless of activity.
     */
    fun messagesPerDay(guildId: Long, days: Int = DEFAULT_DAYS): List<DailyPoint> {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val since = today.minusDays((days - 1).toLong())
        val rowsByDay = messageDailyCounts.findByGuildSince(guildId, since)
            .associate { it.dayStart to it.count.toDouble() }
        return fillRange(since, today).map { day ->
            DailyPoint(day, rowsByDay[day] ?: 0.0)
        }
    }

    /**
     * Voice-hours-per-day for [guildId] over the trailing [days]
     * calendar dates (UTC), ascending. Each closed session is split
     * across the calendar days it touched — a 23:50→00:10 session
     * contributes ~10 minutes to two consecutive days, not 20 minutes
     * to whichever day it "belongs" to.
     */
    fun voiceHoursPerDay(guildId: Long, days: Int = DEFAULT_DAYS): List<DailyPoint> {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val since = today.minusDays((days - 1).toLong())
        val fromInstant = since.atStartOfDay(ZoneOffset.UTC).toInstant()
        val untilInstant = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val sessions = voiceSessions.findClosedOverlapping(guildId, fromInstant, untilInstant)

        val secondsByDay = mutableMapOf<LocalDate, Long>()
        for (session in sessions) {
            val sessionStart = session.joinedAt
            val sessionEnd = session.leftAt ?: continue
            // Walk day-by-day from the session's start day to its end day,
            // adding the overlap with each [day, day+1) bucket.
            var cursor = sessionStart.atZone(ZoneOffset.UTC).toLocalDate()
            val endDay = sessionEnd.atZone(ZoneOffset.UTC).toLocalDate()
            while (cursor <= endDay) {
                val bucketStart = cursor.atStartOfDay(ZoneOffset.UTC).toInstant()
                val bucketEnd = cursor.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                val overlapStart = max(sessionStart.epochSecond, bucketStart.epochSecond)
                val overlapEnd = min(sessionEnd.epochSecond, bucketEnd.epochSecond)
                val overlapSeconds = overlapEnd - overlapStart
                if (overlapSeconds > 0 && cursor in since..today) {
                    secondsByDay.merge(cursor, overlapSeconds) { a, b -> a + b }
                }
                cursor = cursor.plusDays(1)
            }
        }

        return fillRange(since, today).map { day ->
            val seconds = secondsByDay[day] ?: 0L
            DailyPoint(day, seconds / SECONDS_PER_HOUR)
        }
    }

    private fun toChartView(series: List<DailyPoint>): ChartView = ChartView(
        points = series,
        polyline = polylinePoints(series),
        total = series.sumOf { it.value },
        max = series.maxOfOrNull { it.value } ?: 0.0,
        viewBoxWidth = CHART_WIDTH,
        viewBoxHeight = CHART_HEIGHT,
        firstDate = series.firstOrNull()?.date,
        lastDate = series.lastOrNull()?.date,
    )

    /**
     * Build the `points="..."` value for an `<svg><polyline>` rendering
     * of [series]. Y-axis normalises to the series max; an all-zero
     * series renders along the bottom of the chart so the baseline is
     * always visible. The chart is padded inside the SVG so the top
     * edge doesn't clip the highest point.
     *
     * Internal — the controller never calls this directly; templates
     * read [ChartView.polyline] from the bundle [buildMessagesChart]
     * returns.
     */
    internal fun polylinePoints(series: List<DailyPoint>): String {
        if (series.isEmpty()) return ""
        val padding = 8.0
        val innerW = CHART_WIDTH - 2 * padding
        val innerH = CHART_HEIGHT - 2 * padding
        val maxVal = series.maxOf { it.value }.coerceAtLeast(1.0)
        val stepX = if (series.size <= 1) 0.0 else innerW / (series.size - 1)
        return series.mapIndexed { i, point ->
            val x = padding + i * stepX
            val y = padding + innerH - (point.value / maxVal) * innerH
            "%.1f,%.1f".format(x, y)
        }.joinToString(" ")
    }

    private fun fillRange(since: LocalDate, today: LocalDate): List<LocalDate> {
        val length = Duration.between(
            since.atStartOfDay(ZoneOffset.UTC),
            today.plusDays(1).atStartOfDay(ZoneOffset.UTC),
        ).toDays().toInt()
        return (0 until length).map { since.plusDays(it.toLong()) }
    }

    companion object {
        const val DEFAULT_DAYS: Int = 30
        private const val SECONDS_PER_HOUR: Double = 3600.0

        /** Width / height for the per-chart SVG. Picked to match the moderation panel grid. */
        const val CHART_WIDTH: Int = 600
        const val CHART_HEIGHT: Int = 180
    }
}
