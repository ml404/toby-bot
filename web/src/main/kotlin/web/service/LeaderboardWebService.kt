package web.service

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
) {

    companion object {
        // Keep the list tight so the leaderboard page stays scannable. Users
        // below this don't meaningfully change the story of "who owns coin".
        const val TOBY_COIN_LEADERBOARD_LIMIT = 10
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
        sort: LeaderboardSort = LeaderboardSort.THIS_MONTH
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

        return LeaderboardGuildView(
            guildName = guild.name,
            podium = resorted.take(3),
            standings = resorted.drop(3),
            totalCreditsThisMonth = totalCreditsThisMonth,
            totalVoiceThisMonth = totalVoiceThisMonth,
            mostActiveMember = mostActiveName,
            totalMembers = resorted.size,
            sort = sort,
            tobyCoinLeaders = buildTobyCoinLeaders(guildId)
        )
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
    val tobyCoinLeaders: List<TobyCoinLeaderRow> = emptyList()
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
