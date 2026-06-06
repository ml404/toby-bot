package web.service

import database.dto.activity.InstallEventType
import database.service.activity.InstallEventService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the monthly install-growth + churn bar chart for the operator
 * `/admin/installs` page. One bar group per calendar month over a trailing
 * window, each with an "installs" bar and a "removals" bar:
 *
 *  - **Installs** come from the wizard `INSTALLED_AT` timestamps carried on
 *    the [AdminInstallsService.InstallRow]s — historical and available
 *    retroactively, but only counts guilds that completed the wizard.
 *  - **Removals** come from the [InstallEventService] LEAVE ledger — exact,
 *    but only from the ledger's deploy onward (zero for earlier months).
 *
 * The two sources differ deliberately: install dates exist in config
 * history, but a departed guild leaves no config trace, so its removal can
 * only come from the event ledger. The page caption notes this.
 *
 * Rendered as CSS bars (heights are pre-computed percentages) rather than
 * the SVG line used by the Activity tab — grouped monthly counts read more
 * clearly as bars, and it keeps this service free of SVG geometry.
 */
@Service
class InstallChartsService(
    private val installEventService: InstallEventService,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class MonthBucket(
        val label: String,
        val installs: Int,
        val removals: Int,
        val net: Int,
        /** 0..100 — bar height as a percentage of the busiest month. */
        val installsHeightPct: Int,
        val removalsHeightPct: Int,
    )

    data class InstallChartView(
        val buckets: List<MonthBucket>,
        val maxValue: Int,
        val totalInstalls: Int,
        val totalRemovals: Int,
    ) {
        val isEmpty: Boolean get() = maxValue == 0
        val netTotal: Int get() = totalInstalls - totalRemovals
    }

    fun build(rows: List<AdminInstallsService.InstallRow>, months: Int = DEFAULT_MONTHS): InstallChartView {
        val thisMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC))
        val window = (months - 1 downTo 0).map { thisMonth.minusMonths(it.toLong()) }
        val windowSet = window.toSet()

        val installsByMonth: Map<YearMonth, Int> = rows
            .mapNotNull { it.installedAtMillis?.let(::toYearMonth) }
            .filter { it in windowSet }
            .groupingBy { it }
            .eachCount()

        val since = window.first().atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val removalsByMonth: Map<YearMonth, Int> = installEventService.findSince(since)
            .asSequence()
            .filter { it.eventType == InstallEventType.LEAVE.name }
            .map { toYearMonth(it.occurredAt.toEpochMilli()) }
            .filter { it in windowSet }
            .groupingBy { it }
            .eachCount()

        val maxValue = window.maxOfOrNull { ym ->
            maxOf(installsByMonth[ym] ?: 0, removalsByMonth[ym] ?: 0)
        } ?: 0

        val buckets = window.map { ym ->
            val installs = installsByMonth[ym] ?: 0
            val removals = removalsByMonth[ym] ?: 0
            MonthBucket(
                label = LABEL_FORMAT.format(ym),
                installs = installs,
                removals = removals,
                net = installs - removals,
                installsHeightPct = pct(installs, maxValue),
                removalsHeightPct = pct(removals, maxValue),
            )
        }

        return InstallChartView(
            buckets = buckets,
            maxValue = maxValue,
            totalInstalls = buckets.sumOf { it.installs },
            totalRemovals = buckets.sumOf { it.removals },
        )
    }

    companion object {
        const val DEFAULT_MONTHS: Int = 12
        private val LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

        private fun toYearMonth(epochMillis: Long): YearMonth =
            YearMonth.from(java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))

        private fun pct(value: Int, max: Int): Int =
            if (max <= 0) 0 else ((value.toDouble() / max) * 100).toInt()
    }
}
