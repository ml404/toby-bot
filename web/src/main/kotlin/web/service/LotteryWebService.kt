package web.service

import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.LotteryHelper
import database.service.TitleService
import database.service.UserService
import org.springframework.stereotype.Service
import web.view.BulkBonusTierView
import web.view.LotteryIncentivesView
import web.view.MultiplierTierView
import web.view.PoolMilestoneView

/**
 * Thin orchestration layer around [JackpotLotteryService] for the web
 * lottery page. Exposes view-friendly snapshots and ticket-buy outcome
 * mapping; doesn't introduce new lottery semantics.
 *
 * Mirrors the shape of `BlackjackWebService` / `CasinoHoldemWebService`:
 * controller passes user inputs in, service returns DTO-style data
 * objects the controller wraps for JSON responses.
 */
@Service
class LotteryWebService(
    private val jackpotLotteryService: JackpotLotteryService,
    private val configService: ConfigService,
    private val memberLookupHelper: MemberLookupHelper,
    private val userService: UserService,
    private val titleService: TitleService,
) {

    /**
     * Daily-draw + featured weighted-event snapshot for the page render.
     * Either field can be null when no lottery of that mode is open.
     *
     * `dailyMode` + `dailyEnabled` reflect the guild's `LOTTERY_DAILY_*`
     * config so the page can branch its copy: a WEIGHTED-mode guild
     * should not see the Pick-5 picker or the 5/4/3/2-match tier table,
     * and a paused guild (`LOTTERY_DAILY_ENABLED=false`) should see an
     * explicit empty state instead of a stale picker.
     */
    data class LotteryPageSnapshot(
        val dailyOpen: JackpotLotteryDto?,
        val dailyLatestDrawn: JackpotLotteryDto?,
        val dailyMyTicket: JackpotLotteryTicketDto?,
        val dailyTicketBuyers: Int,
        val weightedOpen: JackpotLotteryDto?,
        val weightedMyTicket: JackpotLotteryTicketDto?,
        val weightedTopHolders: List<TopHolder>,
        val weightedTotalTickets: Long,
        val pickCount: Int,
        val numberMax: Int,
        val tierPercents: List<Int>,
        val revenueJackpotPct: Long,
        val dailyMode: String,
        val dailyEnabled: Boolean,
        /**
         * Active participation-incentive tiers for the open WEIGHTED
         * lottery. Empty when no lottery is open, the lottery is
         * NUMBER_MATCH-only, or no tiers are configured. Mirrors the
         * moderation page's `lotteryIncentives` so both surfaces show
         * the same rules verbatim.
         */
        val weightedIncentives: LotteryIncentivesView = LotteryIncentivesView.empty(),
    )

    /**
     * Display projection for a single weighted-lottery top holder.
     * Carries the Discord display name + avatar URL alongside the
     * ticket count so the lottery page can render the same
     * avatar-and-name + title-pill member cell as the leaderboard.
     * `title` is the user's purchased active title (same source as the
     * leaderboard's `lb-title-pill`); null when unset or unresolvable.
     */
    data class TopHolder(
        val discordId: Long,
        val ticketCount: Int,
        val name: String,
        val avatarUrl: String?,
        val title: String?,
    )

    fun snapshot(guildId: Long, discordId: Long): LotteryPageSnapshot {
        val dailyOpen = jackpotLotteryService.getOpenMatch(guildId)
        // If the open daily exists, that's "latest"; otherwise the most-
        // recent DRAWN row (still useful to show last result).
        val dailyLatest = dailyOpen ?: jackpotLotteryService.getLatestMatch(guildId)
        val dailyMyTicket = jackpotLotteryService.userTicketForOpenMatch(guildId, discordId)
        val dailyBuyers = jackpotLotteryService.ticketsForOpenMatch(guildId).size

        val weightedOpen = jackpotLotteryService.getOpenWeighted(guildId)
        val weightedTickets = jackpotLotteryService.ticketsForOpenWeighted(guildId)
        val weightedMyTicket = weightedTickets.firstOrNull { it.discordId == discordId }
        val weightedTopRaw = weightedTickets.sortedByDescending { it.ticketCount }.take(5)
        val displays = memberLookupHelper.resolveAll(guildId, weightedTopRaw.map { it.discordId })
        val weightedTop = weightedTopRaw.map { ticket ->
            val display = displays[ticket.discordId]
            // Mirrors LeaderboardWebService.buildTobyCoinLeaders: a missing /
            // stale title id must not break the snapshot, so swallow with
            // runCatching and fall back to null (no pill rendered).
            val title = runCatching {
                userService.getUserById(ticket.discordId, guildId)
                    ?.activeTitleId
                    ?.let { titleService.getById(it) }
                    ?.label
            }.getOrNull()
            TopHolder(
                discordId = ticket.discordId,
                ticketCount = ticket.ticketCount,
                name = display?.name ?: memberLookupHelper.fallbackName(ticket.discordId),
                avatarUrl = display?.avatarUrl,
                title = title,
            )
        }
        val weightedTotal = weightedTickets.sumOf { it.ticketCount.toLong() }

        // Resolve incentives only when there's an open weighted lottery
        // (they don't apply to NUMBER_MATCH, and an empty list when no
        // tiers are configured means the panel won't render at all).
        val incentives = if (weightedOpen != null) {
            LotteryIncentivesView(
                bulkTiers = LotteryHelper.bulkBonusTiers(configService, guildId)
                    .map { (buy, bonus) -> BulkBonusTierView(buy = buy, bonus = bonus) },
                multiplierTiers = LotteryHelper.volumeMultiplierTiers(configService, guildId)
                    .map { (total, bp) -> MultiplierTierView(total = total, bp = bp) },
                poolMilestones = LotteryHelper.poolMilestones(configService, guildId)
                    .map { (tickets, pct) -> PoolMilestoneView(tickets = tickets, pct = pct) },
            )
        } else LotteryIncentivesView.empty()

        return LotteryPageSnapshot(
            dailyOpen = dailyOpen,
            dailyLatestDrawn = dailyLatest,
            dailyMyTicket = dailyMyTicket,
            dailyTicketBuyers = dailyBuyers,
            weightedOpen = weightedOpen,
            weightedMyTicket = weightedMyTicket,
            weightedTopHolders = weightedTop,
            weightedTotalTickets = weightedTotal,
            pickCount = LotteryHelper.MATCH_PICK_COUNT,
            numberMax = LotteryHelper.MATCH_NUMBER_MAX,
            tierPercents = LotteryHelper.TIER_PCTS_5_4_3_2.toList(),
            revenueJackpotPct = LotteryHelper.dailyRevenueJackpotPct(configService, guildId),
            dailyMode = LotteryHelper.dailyMode(configService, guildId),
            dailyEnabled = LotteryHelper.dailyEnabled(configService, guildId),
            weightedIncentives = incentives,
        )
    }

    /** Buy a NUMBER_MATCH ticket. Wraps the underlying service 1:1. */
    fun buyMatch(
        guildId: Long,
        discordId: Long,
        picks: List<Int>,
    ): JackpotLotteryService.BuyMatchOutcome =
        jackpotLotteryService.buyMatchTicket(guildId, discordId, picks)

    /** Buy [count] weighted tickets. Wraps the underlying service 1:1. */
    fun buyWeighted(
        guildId: Long,
        discordId: Long,
        count: Int,
    ): JackpotLotteryService.BuyOutcome =
        jackpotLotteryService.buyTickets(guildId, discordId, count)
}
