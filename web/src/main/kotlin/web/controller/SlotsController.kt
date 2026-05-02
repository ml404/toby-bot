package web.controller

import database.economy.SlotMachine
import database.service.SlotsService
import database.service.SlotsService.SpinOutcome
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
import web.service.EconomyWebService
import web.util.WebGuildAccess

/**
 * Web surface for the `/slots` minigame. GET renders the reel UI with the
 * user's current credit balance and the payout table; POST runs the spin
 * via [SlotsService.spin] and returns JSON for the JS animation to settle
 * the reels onto.
 *
 * Both surfaces share [SlotsService] so Discord and web can't drift on
 * payout maths, debit/credit semantics, or stake bounds.
 */
@Controller
@RequestMapping("/casino/{guildId}/slots")
class SlotsController(
    private val slotsService: SlotsService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
) {

    private val errors = CasinoOutcomeMapper { msg -> SpinResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/leaderboards"
    ) { discordId ->
        pageContext.populate(model, guildId, discordId, user) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/leaderboards"
        }
        model.addAttribute("minStake", SlotMachine.MIN_STAKE)
        model.addAttribute("maxStake", SlotMachine.MAX_STAKE)
        model.addAttribute("payoutTable", payoutRows())
        "slots"
    }

    @PostMapping("/spin")
    @ResponseBody
    fun spin(
        @PathVariable guildId: Long,
        @RequestBody request: SpinRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<SpinResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = slotsService.spin(discordId, guildId, request.stake, request.autoTopUp)) {
            is SpinOutcome.Win -> ResponseEntity.ok(
                SpinResponse(
                    ok = true,
                    symbols = outcome.symbols.map { it.display },
                    multiplier = outcome.multiplier,
                    payout = outcome.payout,
                    net = outcome.net,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                )
            )

            is SpinOutcome.Lose -> ResponseEntity.ok(
                SpinResponse(
                    ok = true,
                    symbols = outcome.symbols.map { it.display },
                    multiplier = 0L,
                    payout = 0L,
                    net = -outcome.stake,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.takeIf { it > 0L },
                )
            )

            is SpinOutcome.InsufficientCredits -> errors.insufficientCredits(outcome.stake, outcome.have)
            is SpinOutcome.InsufficientCoinsForTopUp -> errors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            is SpinOutcome.InvalidStake -> errors.invalidStake(outcome.min, outcome.max)
            SpinOutcome.UnknownUser -> errors.unknownUser()
        }
    }

    // Render-side projection of the payout table. Exposed via Model so the
    // template doesn't have to re-derive multipliers — single source of
    // truth lives in SlotMachine.
    private fun payoutRows(): List<PayoutRow> {
        return SlotMachine.DEFAULT_PAYOUTS.entries
            .sortedBy { it.value }
            .map { (symbol, multiplier) ->
                PayoutRow(
                    symbols = "${symbol.display} ${symbol.display} ${symbol.display}",
                    multiplier = "${multiplier}×"
                )
            }
    }
}

data class SpinRequest(val stake: Long = 0, val autoTopUp: Boolean = false)

data class SpinResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val symbols: List<String>? = null,
    val multiplier: Long? = null,
    val payout: Long? = null,
    val net: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike

data class PayoutRow(val symbols: String, val multiplier: String)
