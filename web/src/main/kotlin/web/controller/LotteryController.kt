package web.controller

import database.dto.JackpotLotteryDto
import database.service.JackpotLotteryService.BuyMatchOutcome
import database.service.JackpotLotteryService.BuyOutcome
import database.service.LotteryHelper
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.casino.CasinoPageContext
import web.casino.CasinoResponseLike
import web.casino.renderMinigamePage
import web.service.EconomyWebService
import web.service.LotteryWebService
import web.util.WebGuildAccess

/**
 * Web surface for the per-guild lottery page.
 *
 * - `GET  /casino/{guildId}/lottery` — picker UI for the daily
 *   match-numbers draw, last-result panel, and (when running) the
 *   admin-fired weighted lottery card.
 * - `POST /casino/{guildId}/lottery/match/buy` — submits a 5-number
 *   pick set; debits the user; routes 30% of cost to the jackpot pool
 *   (default) and 70% to the day's prize pool.
 * - `POST /casino/{guildId}/lottery/weighted/buy` — buys N tickets in
 *   the open weighted lottery, mirroring the existing /lottery buy
 *   Discord command.
 *
 * Both POSTs share the same shape as other casino endpoints
 * (`{ ok: true, ... }` on success, `{ ok: false, error: ... }` on
 * failure) so the shared `casino-game.js` scaffold can drive them.
 */
@Controller
@RequestMapping("/casino/{guildId}/lottery")
class LotteryController(
    private val lotteryWebService: LotteryWebService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
) {

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra,
        template = "lottery", lobbyPath = "/casino/guilds"
    ) {
        val discordId = user.attributes["id"].toString().toLong()
        val snap = lotteryWebService.snapshot(guildId, discordId)
        addAttribute("snapshot", LotteryViewModel.from(snap))
    }

    @PostMapping("/match/buy")
    @ResponseBody
    fun buyMatch(
        @PathVariable guildId: Long,
        @RequestBody request: BuyMatchRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BuyMatchResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { httpStatus ->
            val msg = if (httpStatus == 401) "Sign in first." else "You're not a member of that server."
            ResponseEntity.status(httpStatus).body(BuyMatchResponse(ok = false, error = msg))
        }
    ) { discordId ->
        when (val outcome = lotteryWebService.buyMatch(guildId, discordId, request.picks)) {
            is BuyMatchOutcome.Ok -> ResponseEntity.ok(
                BuyMatchResponse(
                    ok = true,
                    pickedNumbers = outcome.pickedNumbers,
                    totalSpent = outcome.totalSpent,
                    newBalance = outcome.newBalance,
                    newPool = outcome.newPool,
                    jackpotInflow = outcome.jackpotInflow,
                )
            )

            is BuyMatchOutcome.InvalidPicks ->
                ResponseEntity.badRequest().body(BuyMatchResponse(ok = false, error = outcome.reason))
            is BuyMatchOutcome.Insufficient ->
                ResponseEntity.badRequest().body(
                    BuyMatchResponse(
                        ok = false,
                        error = "You only have ${outcome.have} credits, need ${outcome.need}.",
                    )
                )
            BuyMatchOutcome.NoOpenLottery ->
                ResponseEntity.status(404).body(BuyMatchResponse(ok = false, error = "No daily lottery is open right now."))
            BuyMatchOutcome.AlreadyBought ->
                ResponseEntity.badRequest().body(
                    BuyMatchResponse(ok = false, error = "You already bought a ticket for today's draw.")
                )
            BuyMatchOutcome.UnknownUser ->
                ResponseEntity.status(404).body(BuyMatchResponse(ok = false, error = "No user record found."))
        }
    }

    @PostMapping("/weighted/buy")
    @ResponseBody
    fun buyWeighted(
        @PathVariable guildId: Long,
        @RequestBody request: BuyWeightedRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BuyWeightedResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { httpStatus ->
            val msg = if (httpStatus == 401) "Sign in first." else "You're not a member of that server."
            ResponseEntity.status(httpStatus).body(BuyWeightedResponse(ok = false, error = msg))
        }
    ) { discordId ->
        when (val outcome = lotteryWebService.buyWeighted(guildId, discordId, request.count)) {
            is BuyOutcome.Ok -> ResponseEntity.ok(
                BuyWeightedResponse(
                    ok = true,
                    ticketCount = outcome.ticketCount,
                    totalSpent = outcome.totalSpent,
                    newBalance = outcome.newBalance,
                    newPool = outcome.newPool,
                    bonusTicketsGranted = outcome.bonusTicketsGranted.takeIf { it > 0L },
                    totalBonusTickets = outcome.totalBonusTickets.takeIf { it > 0L },
                    milestoneBonuses = outcome.milestoneBonuses
                        .map { MilestoneBonusView(threshold = it.threshold, creditsAdded = it.creditsAdded) }
                        .takeIf { it.isNotEmpty() },
                )
            )

            is BuyOutcome.InvalidCount ->
                ResponseEntity.badRequest().body(BuyWeightedResponse(ok = false, error = "Ticket count must be positive."))
            is BuyOutcome.Insufficient ->
                ResponseEntity.badRequest().body(
                    BuyWeightedResponse(
                        ok = false,
                        error = "You only have ${outcome.have} credits, need ${outcome.need}.",
                    )
                )
            BuyOutcome.NoOpenLottery ->
                ResponseEntity.status(404).body(BuyWeightedResponse(ok = false, error = "No weighted lottery is open right now."))
            BuyOutcome.UnknownUser ->
                ResponseEntity.status(404).body(BuyWeightedResponse(ok = false, error = "No user record found."))
        }
    }
}

data class BuyMatchRequest(val picks: List<Int> = emptyList())
data class BuyMatchResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val pickedNumbers: List<Int>? = null,
    val totalSpent: Long? = null,
    val newBalance: Long? = null,
    val newPool: Long? = null,
    val jackpotInflow: Long? = null,
) : CasinoResponseLike

data class BuyWeightedRequest(val count: Int = 0)
data class BuyWeightedResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val ticketCount: Int? = null,
    val totalSpent: Long? = null,
    val newBalance: Long? = null,
    val newPool: Long? = null,
    /**
     * Bulk-buy bonus tickets credited on this single purchase. Null
     * (rather than 0) when nothing was awarded so the client can
     * cleanly skip the bonus toast without a falsy-zero check.
     */
    val bonusTicketsGranted: Long? = null,
    /**
     * Cumulative bonus tickets the buyer holds on this lottery after
     * the purchase. Mirrors `JackpotLotteryTicketDto.bonusTickets` so
     * the client can render the "X paid + Y bonus" breakdown without
     * a separate snapshot round-trip.
     */
    val totalBonusTickets: Long? = null,
    /**
     * Pool-growth milestones that fired during this purchase. Each
     * entry is `{threshold, creditsAdded}` — same shape as the
     * service's [database.service.JackpotLotteryService.MilestoneBonus].
     * Null when no milestones fired (the client doesn't need an empty
     * list to know there's nothing to render).
     */
    val milestoneBonuses: List<MilestoneBonusView>? = null,
) : CasinoResponseLike

/**
 * View projection of a single pool-growth milestone that fired during
 * a weighted ticket buy. Mirrors
 * [database.service.JackpotLotteryService.MilestoneBonus] but lives in
 * the controller layer so the response shape doesn't leak the service
 * type into the JSON contract.
 */
data class MilestoneBonusView(val threshold: Long, val creditsAdded: Long)

/**
 * View-friendly projection of the lottery snapshot. Strips DTO
 * persistence noise (id, ticketCount on NUMBER_MATCH where it's
 * always 1) and pre-parses comma-separated numbers into List<Int> so
 * Thymeleaf can iterate cleanly.
 */
data class LotteryViewModel(
    val pickCount: Int,
    val numberMax: Int,
    val tierPercents: List<Int>,
    val revenueJackpotPct: Long,
    val daily: DailyView?,
    val weighted: WeightedView?,
    /** True iff the daily lottery is enabled in moderation. When false the
     *  page renders a paused empty-state instead of a stale picker. */
    val dailyEnabled: Boolean,
    /** "NUMBER_MATCH" (Pick-X picker + tier table) or "WEIGHTED" (top-3
     *  weighted draw — shares DOM with the featured-event card and reads
     *  from `weighted` rather than `daily`). The Featured-event card only
     *  shows in NUMBER_MATCH mode, since a WEIGHTED daily already occupies
     *  the single weighted-row slot. */
    val dailyMode: String,
    /** Convenience: true when `dailyMode == "WEIGHTED"`. Avoids string
     *  comparisons in Thymeleaf. */
    val isDailyWeighted: Boolean,
) {
    data class DailyView(
        val pool: Long,
        val ticketPrice: Long,
        val opensAtMillis: Long,
        val closesAtMillis: Long,
        val status: String,
        val isOpen: Boolean,
        val ticketBuyers: Int,
        val myPicks: List<Int>?,
        val drawnNumbers: List<Int>?,
        val myMatches: Int?,
    )

    data class WeightedView(
        val pool: Long,
        val ticketPrice: Long,
        val winnerCount: Int,
        val opensAtMillis: Long,
        val closesAtMillis: Long,
        val totalTickets: Long,
        val myTickets: Int,
        val myBonusTickets: Long,
        val mySpent: Long,
        val topHolders: List<TopHolder>,
        /**
         * Resolved participation-incentive surface for the open
         * weighted lottery: active rules + personalised next-threshold
         * hints + next-to-fire milestone progress. Null when no
         * incentives are configured — the player template hides the
         * whole panel in that case.
         */
        val incentives: WeightedIncentivesPanel?,
    )

    /**
     * What the player sees in the "Participation incentives" panel on
     * the lottery page. Only carries data that has something to render:
     * `nextMultiplierHint` is null when the viewer already holds the
     * top multiplier tier, and `milestoneProgress` is null when no
     * future milestone is configured (the panel just shows active
     * rules in that case).
     *
     * Note: there is intentionally no `nextBulkHint`. Bulk bonus is
     * awarded **per-purchase** — one `/lottery buy N` call must
     * satisfy `N >= tier.buy` on its own, regardless of how many
     * tickets the player already holds. A previous version computed
     * `gap = tier.buy - myTickets` and produced misleading copy like
     * "buy 5 more in one purchase for +3 free" when the actual
     * requirement was buy-10-or-nothing. The active-rules listing
     * already shows every tier's threshold, which is the only
     * information that's meaningful for a per-purchase reward.
     */
    data class WeightedIncentivesPanel(
        val bulkTiers: List<web.view.BulkBonusTierView>,
        val multiplierTiers: List<web.view.MultiplierTierView>,
        val poolMilestones: List<web.view.PoolMilestoneView>,
        val nextMultiplierHint: NextMultiplierHint?,
        val milestoneProgress: MilestoneProgress?,
    )

    /** Smallest unmatched multiplier tier, with the bp pre-formatted to a human "1.25×" decimal. */
    data class NextMultiplierHint(val gap: Long, val multiplier: String, val threshold: Long)

    /**
     * The next-to-fire pool milestone, plus current vs threshold ticket
     * counts so the template can render a `<progress>` bar. Already
     * filtered by `milestonesFired`: only shows milestones strictly
     * greater than the highest fired, so a re-render after a milestone
     * pops never shows the just-fired one as "still ahead".
     */
    data class MilestoneProgress(
        val currentTickets: Long,
        val thresholdTickets: Long,
        val pct: Long,
    )

    /** View projection of a weighted-lottery top holder — Discord
     *  display name + avatar URL + purchased title come from
     *  [LotteryWebService.TopHolder] so the lottery page renders the
     *  same member cell + title pill as the leaderboard. Falls back
     *  to a `Player <last-4>` placeholder when the holder has left
     *  the guild; `title` is null when unset / unresolvable (no pill). */
    data class TopHolder(
        val discordId: Long,
        val ticketCount: Int,
        /** Bulk-bonus tickets the holder has accumulated. The template
         *  renders "X paid + Y bonus" when this is non-zero, matching
         *  the "Your tickets" line treatment. */
        val bonusTickets: Long,
        val name: String,
        val avatarUrl: String?,
        val title: String?,
    )

    companion object {
        fun from(snap: LotteryWebService.LotteryPageSnapshot): LotteryViewModel {
            val daily = (snap.dailyOpen ?: snap.dailyLatestDrawn)?.let { row ->
                val drawn = parseCsv(row.drawnNumbers)
                val mine = parseCsv(snap.dailyMyTicket?.pickedNumbers)
                val matches = if (mine.isNotEmpty() && drawn.isNotEmpty()) mine.count { it in drawn } else null
                DailyView(
                    pool = row.poolAmount,
                    ticketPrice = row.ticketPrice,
                    opensAtMillis = row.openedAt.toEpochMilli(),
                    closesAtMillis = row.closesAt.toEpochMilli(),
                    status = row.status,
                    isOpen = row.status == JackpotLotteryDto.STATUS_OPEN,
                    ticketBuyers = snap.dailyTicketBuyers,
                    myPicks = mine.takeIf { it.isNotEmpty() },
                    drawnNumbers = drawn.takeIf { it.isNotEmpty() },
                    myMatches = matches,
                )
            }
            val weighted = snap.weightedOpen?.let { row ->
                val myTickets = (snap.weightedMyTicket?.ticketCount ?: 0).toLong()
                val myBonus = snap.weightedMyTicket?.bonusTickets ?: 0L
                val incentives = buildIncentivesPanel(snap, row, myTickets)
                WeightedView(
                    pool = row.poolAmount,
                    ticketPrice = row.ticketPrice,
                    winnerCount = row.winnerCount,
                    opensAtMillis = row.openedAt.toEpochMilli(),
                    closesAtMillis = row.closesAt.toEpochMilli(),
                    totalTickets = snap.weightedTotalTickets,
                    myTickets = snap.weightedMyTicket?.ticketCount ?: 0,
                    myBonusTickets = myBonus,
                    mySpent = snap.weightedMyTicket?.spent ?: 0L,
                    topHolders = snap.weightedTopHolders.map {
                        TopHolder(it.discordId, it.ticketCount, it.bonusTickets, it.name, it.avatarUrl, it.title)
                    },
                    incentives = incentives,
                )
            }
            return LotteryViewModel(
                pickCount = snap.pickCount,
                numberMax = snap.numberMax,
                tierPercents = snap.tierPercents,
                revenueJackpotPct = snap.revenueJackpotPct,
                daily = daily,
                weighted = weighted,
                dailyEnabled = snap.dailyEnabled,
                dailyMode = snap.dailyMode,
                isDailyWeighted = snap.dailyMode == database.service.LotteryHelper.MODE_WEIGHTED,
            )
        }

        private fun parseCsv(csv: String?): List<Int> {
            if (csv.isNullOrBlank()) return emptyList()
            return csv.split(',').mapNotNull { it.trim().toIntOrNull() }
        }

        /**
         * Build the incentives panel for the viewing player on the
         * open weighted lottery. Returns null when no incentives are
         * configured — the template hides the whole panel rather than
         * render an empty card.
         *
         * Hint derivation:
         *  - Bulk tiers carry no personalised "next" hint. Bulk bonus
         *    is per-purchase, so a single buy must satisfy `tier.buy`
         *    on its own; existing holdings don't shrink the threshold.
         *    The active-rules listing covers what the player needs.
         *  - Next multiplier tier: smallest `total` threshold strictly
         *    greater than the viewer's total. Multiplier rendered as
         *    a 1.25× / 1.5× decimal so the template doesn't have to.
         *  - Milestone progress: smallest milestone whose threshold is
         *    strictly greater than both `milestonesFired` (so we don't
         *    show one that has already paid out) and the current
         *    guild-wide ticket count. Pre-filtered here so the
         *    template can just check non-null.
         */
        private fun buildIncentivesPanel(
            snap: LotteryWebService.LotteryPageSnapshot,
            lottery: JackpotLotteryDto,
            myTickets: Long,
        ): WeightedIncentivesPanel? {
            val incentives = snap.weightedIncentives
            if (incentives.isEmpty) return null

            val nextMultiplier = incentives.multiplierTiers
                .firstOrNull { it.total > myTickets }
                ?.let { tier ->
                    NextMultiplierHint(
                        gap = tier.total - myTickets,
                        multiplier = "%.2f×".format(tier.bp / LotteryHelper.MULTIPLIER_BP_IDENTITY.toDouble()),
                        threshold = tier.total,
                    )
                }
            val nextMilestone = incentives.poolMilestones
                .firstOrNull { it.tickets > lottery.milestonesFired && it.tickets > snap.weightedTotalTickets }
                ?.let { tier ->
                    MilestoneProgress(
                        currentTickets = snap.weightedTotalTickets,
                        thresholdTickets = tier.tickets,
                        pct = tier.pct,
                    )
                }

            return WeightedIncentivesPanel(
                bulkTiers = incentives.bulkTiers,
                multiplierTiers = incentives.multiplierTiers,
                poolMilestones = incentives.poolMilestones,
                nextMultiplierHint = nextMultiplier,
                milestoneProgress = nextMilestone,
            )
        }
    }
}
