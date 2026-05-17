package web.service

import database.service.ActivityMonthlyRollupService
import database.service.MonthlyCreditSnapshotService
import database.service.TitleService
import database.service.TobyCoinMarketService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.util.GuildMembership
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.floor

@Service
class LeaderboardWebService(
    private val jda: JDA,
    private val introWebService: IntroWebService,
    private val moderationWebService: ModerationWebService,
    private val userService: UserService,
    private val marketService: TobyCoinMarketService,
    private val titleService: TitleService,
    private val snapshotService: MonthlyCreditSnapshotService,
    private val membership: GuildMembership,
    private val rollupService: ActivityMonthlyRollupService,
) {

    companion object {
        // Keep the list tight so the leaderboard page stays scannable. Users
        // below this don't meaningfully change the story of "who owns coin".
        const val TOBY_COIN_LEADERBOARD_LIMIT = 10
        // Matches /activity server in Discord so the web view tells the same story.
        const val TOP_GAMES_LIMIT = 10
        const val TOP_CONTRIBUTORS_LIMIT = 8
        const val HISTORY_MONTHS = 12
        const val SPARK_WIDTH = 80
        const val SPARK_HEIGHT = 20
    }

    fun getGuildsWhereUserCanView(accessToken: String, discordId: Long): List<LeaderboardGuildCard> {
        return introWebService.getMutualGuilds(accessToken).mapNotNull { info ->
            val guildId = info.id.toLongOrNull() ?: return@mapNotNull null
            if (!isMember(discordId, guildId)) return@mapNotNull null
            val leaderboard = moderationWebService.getLeaderboard(guildId)
            // Pick the top by this-month earnings to match the page's default
            // sort. Only surface someone if they've actually earned > 0 this
            // month — otherwise the card claims a "top earner" with +0, which
            // is misleading at the start of the month.
            val top = leaderboard
                .filter { it.creditsEarnedThisMonth > 0L }
                .maxByOrNull { it.creditsEarnedThisMonth }
            LeaderboardGuildCard(
                id = info.id,
                name = info.name,
                iconUrl = info.iconUrl,
                topName = top?.name,
                topTitle = top?.title,
                topCreditsThisMonth = top?.creditsEarnedThisMonth ?: 0L,
                totalVoiceSeconds = leaderboard.sumOf { it.voiceSecondsThisMonth },
                memberCount = leaderboard.size
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun isMember(discordId: Long, guildId: Long): Boolean = membership.isMember(discordId, guildId)

    fun getGuildView(
        guildId: Long,
        sort: LeaderboardSort = LeaderboardSort.THIS_MONTH,
        topGamesMonth: LocalDate? = null
    ): LeaderboardGuildView? {
        val guild = jda.getGuildById(guildId) ?: return null
        val rawRows = moderationWebService.getLeaderboard(guildId)

        // Moderation service emits rows ordered by lifetime socialCredit. Re-sort
        // + re-rank so rank 1 is always the top of the active view — otherwise
        // lifetime ranks leak into the this-month display.
        val resorted = when (sort) {
            LeaderboardSort.THIS_MONTH -> rawRows.sortedByDescending { it.creditsEarnedThisMonth }
            LeaderboardSort.LIFETIME -> rawRows
        }.mapIndexed { i, r -> r.copy(rank = i + 1) }

        val totalCreditsThisMonth = rawRows.sumOf { it.creditsEarnedThisMonth }
        val totalVoiceThisMonth = rawRows.sumOf { it.voiceSecondsThisMonth }
        // rawRows arrive ordered by current-total socialCredit desc. If nobody
        // has any voice time this month, maxByOrNull returns the first row
        // (tied-max at 0) — i.e. the lifetime leader — and the "Most active
        // this month" card silently misrepresents them. Filter out the zeros
        // so the card hides itself in that case (template guards on null).
        val mostActiveName = rawRows
            .filter { it.voiceSecondsThisMonth > 0L }
            .maxByOrNull { it.voiceSecondsThisMonth }
            ?.name

        val topGamesPanel = buildTopGamesPanel(guild, guildId, topGamesMonth)

        return LeaderboardGuildView(
            guildName = guild.name,
            podium = resorted.take(3),
            // The Members tab shows the full ranked list, top 3 included.
            // The podium above the tabs is a visual hero, not a substitute for
            // the top 3 rows in the standings table.
            standings = resorted,
            totalCreditsThisMonth = totalCreditsThisMonth,
            totalVoiceThisMonth = totalVoiceThisMonth,
            mostActiveMember = mostActiveName,
            totalMembers = resorted.size,
            sort = sort,
            tobyCoinLeaders = buildTobyCoinLeaders(guildId),
            topGames = topGamesPanel.rows,
            topGamesMonth = topGamesPanel.selectedMonth,
            topGamesMonthOptions = topGamesPanel.monthOptions,
        )
    }

    private data class TopGamesPanel(
        val rows: List<TopGameRow>,
        val selectedMonth: LocalDate,
        val monthOptions: List<MonthOption>,
    )

    private fun buildTopGamesPanel(
        guild: net.dv8tion.jda.api.entities.Guild,
        guildId: Long,
        requestedMonth: LocalDate?,
    ): TopGamesPanel {
        val thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val oldest = thisMonth.minusMonths((HISTORY_MONTHS - 1).toLong())
        val selectedMonth = clampMonth(requestedMonth, thisMonth, oldest) ?: thisMonth

        // Mirror /activity server: skip rollup rows owned by users who have
        // explicitly opted out of tracking, even though their old rows still exist.
        val optedOut = runCatching {
            userService.listGuildUsers(guildId)
                .filterNotNull()
                .filter { it.activityTrackingOptOut }
                .map { it.discordId }
                .toSet()
        }.getOrDefault(emptySet())

        // One query covers the whole panel: top games, contributors, deltas,
        // sparklines all read from the same in-memory slice.
        val all = runCatching { rollupService.forGuildSince(guildId, oldest) }
            .getOrDefault(emptyList())
            .filter { it.discordId !in optedOut }

        val rowsByMonth: Map<LocalDate, List<database.dto.ActivityMonthlyRollupDto>> = all.groupBy { it.monthStart }
        val totalsByMonthGame: Map<LocalDate, Map<String, Long>> = rowsByMonth.mapValues { (_, rs) ->
            rs.groupBy { it.activityName }.mapValues { (_, gr) -> gr.sumOf { it.seconds } }
        }
        val selectedTotals = totalsByMonthGame[selectedMonth].orEmpty()
        val prevTotals = totalsByMonthGame[selectedMonth.minusMonths(1)].orEmpty()

        val months: List<LocalDate> = (0 until HISTORY_MONTHS)
            .map { thisMonth.minusMonths(it.toLong()) }
            .reversed()

        val topRows = selectedTotals.entries
            .sortedByDescending { it.value }
            .take(TOP_GAMES_LIMIT)
            .mapIndexed { index, entry ->
                val activityName = entry.key
                val totalSeconds = entry.value
                val contributors = buildContributors(
                    guild = guild,
                    rows = rowsByMonth[selectedMonth].orEmpty().filter { it.activityName == activityName },
                    totalSeconds = totalSeconds,
                )
                val prev = prevTotals[activityName] ?: 0L
                val history = months.map { m -> totalsByMonthGame[m]?.get(activityName) ?: 0L }
                TopGameRow(
                    rank = index + 1,
                    name = activityName,
                    seconds = totalSeconds,
                    contributors = contributors,
                    deltaSeconds = totalSeconds - prev,
                    isNew = prev == 0L && totalSeconds > 0L,
                    historySeconds = history,
                    sparklinePolyline = sparklinePolyline(history),
                )
            }

        // `months` is already oldest→newest; emit picker options in the same order
        // so the rendered <select> reads chronologically.
        val monthOptions = months.map { m ->
            MonthOption(
                value = monthValue(m),
                label = monthLabel(m),
                isSelected = m == selectedMonth,
                hasData = (totalsByMonthGame[m]?.isNotEmpty() == true),
            )
        }

        return TopGamesPanel(rows = topRows, selectedMonth = selectedMonth, monthOptions = monthOptions)
    }

    private fun buildContributors(
        guild: net.dv8tion.jda.api.entities.Guild,
        rows: List<database.dto.ActivityMonthlyRollupDto>,
        totalSeconds: Long,
    ): List<TopGameContributor> {
        if (totalSeconds <= 0) return emptyList()
        return rows.groupBy { it.discordId }
            .mapValues { (_, rs) -> rs.sumOf { it.seconds } }
            .entries
            .sortedByDescending { it.value }
            .take(TOP_CONTRIBUTORS_LIMIT)
            .map { (discordId, seconds) ->
                val member = runCatching { guild.getMemberById(discordId) }.getOrNull()
                TopGameContributor(
                    discordId = discordId.toString(),
                    name = member?.effectiveName ?: "Unknown",
                    avatarUrl = member?.effectiveAvatarUrl,
                    seconds = seconds,
                    percent = ((seconds * 100.0) / totalSeconds).toInt().coerceIn(0, 100),
                    playtimeDisplay = formatDurationShort(seconds),
                )
            }
    }

    private fun sparklinePolyline(history: List<Long>): String {
        if (history.isEmpty()) return ""
        val max = history.max().coerceAtLeast(1L)
        val n = history.size
        val xStep = if (n > 1) SPARK_WIDTH.toDouble() / (n - 1) else 0.0
        return history.mapIndexed { i, v ->
            val x = (i * xStep)
            // Invert: SVG y grows downward, but a higher value should plot higher.
            val y = SPARK_HEIGHT - ((v.toDouble() / max) * SPARK_HEIGHT)
            "${formatCoord(x)},${formatCoord(y)}"
        }.joinToString(" ")
    }

    private fun formatCoord(d: Double): String {
        val rounded = (d * 10.0).toInt() / 10.0
        return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
    }

    private fun monthValue(d: LocalDate): String = "%04d-%02d".format(d.year, d.monthValue)
    private fun monthLabel(d: LocalDate): String {
        val name = d.month.name.lowercase().replaceFirstChar { it.titlecase() }
        return "$name ${d.year}"
    }

    private fun clampMonth(requested: LocalDate?, thisMonth: LocalDate, oldest: LocalDate): LocalDate? {
        if (requested == null) return null
        val first = requested.withDayOfMonth(1)
        if (first.isBefore(oldest) || first.isAfter(thisMonth)) return null
        return first
    }

    private fun formatDurationShort(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun buildTobyCoinLeaders(guildId: Long): List<TobyCoinLeaderRow> {
        val guild = jda.getGuildById(guildId) ?: return emptyList()
        val price = marketService.getMarket(guildId)?.price ?: 0.0
        val thisMonthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        // Best-effort: if the snapshot table read fails (schema drift on an old
        // deploy), degrade gracefully and skip the delta rather than 500 the page.
        val baselines = runCatching {
            snapshotService.listForGuildDate(guildId, thisMonthStart)
                .associateBy { it.discordId }
        }.getOrDefault(emptyMap()).toMutableMap()

        val allUsers = userService.listGuildUsers(guildId).filterNotNull()

        // Lazy-baseline: if the scheduled monthly job hasn't yet snapshotted
        // this user for this month's 1st, write one now using their current
        // balance. Mirrors ModerationWebService so both leaderboards share
        // the same baseline and the delta becomes meaningful from the next
        // trade onwards. ModerationWebService usually wins this race because
        // it runs first in getGuildView; this block is a safety net.
        allUsers.forEach { dto ->
            if (!baselines.containsKey(dto.discordId)) {
                runCatching {
                    snapshotService.upsertIfMissing(
                        database.dto.MonthlyCreditSnapshotDto(
                            discordId = dto.discordId,
                            guildId = guildId,
                            snapshotDate = thisMonthStart,
                            socialCredit = dto.socialCredit ?: 0L,
                            tobyCoins = dto.tobyCoins
                        )
                    )
                }.getOrNull()?.let { baselines[dto.discordId] = it }
            }
        }

        return allUsers.asSequence()
            .filter { it.tobyCoins > 0L }
            .sortedByDescending { it.tobyCoins }
            .take(TOBY_COIN_LEADERBOARD_LIMIT)
            .mapIndexed { index, dto ->
                val member = guild.getMemberById(dto.discordId)
                val title = runCatching {
                    dto.activeTitleId?.let { titleService.getById(it) }?.label
                }.getOrNull()
                val coinsThisMonth = baselines[dto.discordId]?.let { dto.tobyCoins - it.tobyCoins } ?: 0L
                TobyCoinLeaderRow(
                    rank = index + 1,
                    discordId = dto.discordId.toString(),
                    name = member?.effectiveName ?: "Unknown",
                    avatarUrl = member?.effectiveAvatarUrl,
                    title = title,
                    coins = dto.tobyCoins,
                    coinsThisMonth = coinsThisMonth,
                    portfolioCredits = floor(dto.tobyCoins.toDouble() * price).toLong()
                )
            }
            .toList()
    }
}

data class LeaderboardGuildCard(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val topName: String?,
    val topTitle: String?,
    val topCreditsThisMonth: Long,
    val totalVoiceSeconds: Long,
    val memberCount: Int
) {
    @Suppress("unused") // consumed by templates/leaderboards.html via Thymeleaf
    val totalVoiceDisplay: String get() = formatDuration(totalVoiceSeconds)
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

data class LeaderboardGuildView(
    val guildName: String,
    val podium: List<LeaderboardRow>,
    val standings: List<LeaderboardRow>,
    val totalCreditsThisMonth: Long,
    val totalVoiceThisMonth: Long,
    val mostActiveMember: String?,
    val totalMembers: Int,
    val sort: LeaderboardSort = LeaderboardSort.THIS_MONTH,
    val tobyCoinLeaders: List<TobyCoinLeaderRow> = emptyList(),
    val topGames: List<TopGameRow> = emptyList(),
    val topGamesMonth: LocalDate? = null,
    val topGamesMonthOptions: List<MonthOption> = emptyList(),
) {
    @Suppress("unused") // consumed by templates/leaderboard.html via Thymeleaf
    val totalVoiceThisMonthDisplay: String get() = formatDuration(totalVoiceThisMonth)
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

enum class LeaderboardSort(val queryValue: String) {
    THIS_MONTH("month"),
    LIFETIME("lifetime");

    companion object {
        fun fromQuery(s: String?): LeaderboardSort =
            entries.firstOrNull { it.queryValue == s } ?: THIS_MONTH
    }
}

data class TobyCoinLeaderRow(
    val rank: Int,
    val discordId: String,
    val name: String,
    val avatarUrl: String?,
    val title: String?,
    val coins: Long,
    val coinsThisMonth: Long = 0L,
    val portfolioCredits: Long
)

data class TopGameRow(
    val rank: Int,
    val name: String,
    val seconds: Long,
    val contributors: List<TopGameContributor> = emptyList(),
    val deltaSeconds: Long = 0L,
    val isNew: Boolean = false,
    val historySeconds: List<Long> = emptyList(),
    val sparklinePolyline: String = "",
) {
    @Suppress("unused") // consumed by templates/leaderboard.html via Thymeleaf
    val playtimeDisplay: String get() = formatDuration(seconds)

    @Suppress("unused") // consumed by templates/leaderboard.html via Thymeleaf
    val deltaDisplay: String
        get() {
            if (isNew) return "NEW"
            if (deltaSeconds == 0L) return ""
            val abs = formatDuration(kotlin.math.abs(deltaSeconds))
            return if (deltaSeconds > 0) "+$abs" else "-$abs"
        }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

data class TopGameContributor(
    val discordId: String,
    val name: String,
    val avatarUrl: String?,
    val seconds: Long,
    val percent: Int,
    val playtimeDisplay: String,
)

data class MonthOption(
    val value: String,
    val label: String,
    val isSelected: Boolean,
    val hasData: Boolean,
)
