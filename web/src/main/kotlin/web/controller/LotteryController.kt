package web.controller

import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto
import database.service.JackpotLotteryService.BuyMatchOutcome
import database.service.JackpotLotteryService.BuyOutcome
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
) : CasinoResponseLike

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
        val mySpent: Long,
        val topHolders: List<TopHolder>,
    )

    data class TopHolder(val discordId: Long, val ticketCount: Int)

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
                WeightedView(
                    pool = row.poolAmount,
                    ticketPrice = row.ticketPrice,
                    winnerCount = row.winnerCount,
                    opensAtMillis = row.openedAt.toEpochMilli(),
                    closesAtMillis = row.closesAt.toEpochMilli(),
                    totalTickets = snap.weightedTotalTickets,
                    myTickets = snap.weightedMyTicket?.ticketCount ?: 0,
                    mySpent = snap.weightedMyTicket?.spent ?: 0L,
                    topHolders = snap.weightedTopHolders.map {
                        TopHolder(it.discordId, it.ticketCount)
                    },
                )
            }
            return LotteryViewModel(
                pickCount = snap.pickCount,
                numberMax = snap.numberMax,
                tierPercents = snap.tierPercents,
                revenueJackpotPct = snap.revenueJackpotPct,
                daily = daily,
                weighted = weighted,
            )
        }

        private fun parseCsv(csv: String?): List<Int> {
            if (csv.isNullOrBlank()) return emptyList()
            return csv.split(',').mapNotNull { it.trim().toIntOrNull() }
        }

        // Suppress unused parameter warning — used by Thymeleaf via reflection.
        @Suppress("unused")
        private fun unused(t: JackpotLotteryTicketDto) = t
    }
}
