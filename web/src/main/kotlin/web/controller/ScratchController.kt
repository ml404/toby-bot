package web.controller

import common.casino.CasinoCommonFailure
import database.economy.ScratchCard
import database.economy.SlotMachine
import database.service.ScratchService
import database.service.ScratchService.ScratchOutcome
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
import web.casino.CasinoOutcomeMapper
import web.casino.CasinoPageContext
import web.casino.CasinoResponseLike
import web.casino.renderMinigamePage
import web.service.EconomyWebService
import web.util.WebGuildAccess
import web.util.positiveOrNull

@Controller
@RequestMapping("/casino/{guildId}/scratch")
class ScratchController(
    private val scratchService: ScratchService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
) {

    private val errors = CasinoOutcomeMapper { msg -> ScratchResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra, template = "scratch"
    ) {
        addAttribute("minStake", ScratchCard.MIN_STAKE)
        addAttribute("maxStake", ScratchCard.MAX_STAKE)
        addAttribute("cellCount", ScratchCard.CELL_COUNT)
        addAttribute("matchThreshold", ScratchCard.MATCH_THRESHOLD)
        addAttribute("matchCounts", (ScratchCard.MATCH_THRESHOLD..ScratchCard.CELL_COUNT).toList())
        addAttribute("payoutTable", scratchPayoutRows())
    }

    // Per-symbol payout row: one cell per match count from MATCH_THRESHOLD
    // to CELL_COUNT. Driven by ScratchCard.multiplierFor() so the table
    // stays locked to the live formula — change the bases or floor in code
    // and the page updates without anyone editing HTML.
    private fun scratchPayoutRows(): List<ScratchPayoutRow> =
        SlotMachine.Symbol.entries.map { symbol ->
            ScratchPayoutRow(
                symbol = symbol.display,
                multipliers = (ScratchCard.MATCH_THRESHOLD..ScratchCard.CELL_COUNT).map { k ->
                    "${ScratchCard.multiplierFor(symbol, k)}×"
                }
            )
        }

    @PostMapping("/scratch")
    @ResponseBody
    fun scratch(
        @PathVariable guildId: Long,
        @RequestBody request: ScratchRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ScratchResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = scratchService.scratch(discordId, guildId, request.stake, request.autoTopUp)) {
            is ScratchOutcome.Win -> ResponseEntity.ok(
                ScratchResponse(
                    ok = true,
                    cells = outcome.cells.map { it.display },
                    winningSymbol = outcome.winningSymbol.display,
                    matchCount = outcome.matchCount,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.positiveOrNull(),
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                )
            )

            is ScratchOutcome.Lose -> ResponseEntity.ok(
                ScratchResponse(
                    ok = true,
                    cells = outcome.cells.map { it.display },
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.positiveOrNull(),
                )
            )

            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }
}

data class ScratchPayoutRow(val symbol: String, val multipliers: List<String>)

data class ScratchRequest(val stake: Long = 0, val autoTopUp: Boolean = false)

data class ScratchResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val cells: List<String>? = null,
    val winningSymbol: String? = null,
    val matchCount: Int? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike
