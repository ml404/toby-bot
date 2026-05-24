package web.controller

import common.casino.CasinoCommonFailure
import common.casino.roulette.Roulette
import database.service.economy.JackpotGame
import database.service.casino.roulette.RouletteService
import database.service.casino.roulette.RouletteService.SpinOutcome
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
import web.casino.StakeBounds
import web.casino.renderMinigamePage
import web.service.EconomyWebService
import web.util.WebGuildAccess
import web.util.positiveOrNull

/**
 * Web surface for the `/roulette` minigame. GET renders the wheel + bet
 * grid with the user's current credit balance and the payout reference;
 * POST runs the spin via [RouletteService.spin] and returns JSON for the
 * JS animation to settle the ball onto.
 *
 * Both surfaces share [RouletteService] so Discord and web can't drift
 * on payout maths, debit/credit semantics, or stake bounds.
 */
@Controller
@RequestMapping("/casino/{guildId}/roulette")
class RouletteController(
    private val rouletteService: RouletteService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
    private val stakeBounds: StakeBounds,
) {

    private val errors = CasinoOutcomeMapper { msg -> RouletteSpinResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra,
        template = "roulette", lobbyPath = "/casino/guilds",
        game = JackpotGame.ROULETTE,
    ) {
        val (minStake, maxStake) = stakeBounds.roulette(guildId)
        addAttribute("minStake", minStake)
        addAttribute("maxStake", maxStake)
        addAttribute("payoutTable", payoutRows())
        addAttribute("redNumbers", Roulette.RED_NUMBERS)
        addAttribute("wheelOrder", Roulette.WHEEL_ORDER)
        addAttribute("maxPocket", Roulette.MAX_NUMBER)
        addAttribute("bets", betRows())
    }

    @PostMapping("/spin")
    @ResponseBody
    fun spin(
        @PathVariable guildId: Long,
        @RequestBody request: RouletteSpinRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<RouletteSpinResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder,
    ) { discordId ->
        val bet = parseBet(request.bet)
            ?: return@requireMemberForJson errors.badRequest("Pick a bet from the list.")

        val outcome = rouletteService.spin(
            discordId, guildId, request.stake, bet, request.number, request.autoTopUp,
        )
        when (outcome) {
            is SpinOutcome.Win -> ResponseEntity.ok(
                RouletteSpinResponse(
                    ok = true,
                    bet = outcome.bet.name,
                    betLabel = outcome.bet.display,
                    landed = outcome.landed,
                    color = outcome.color.name,
                    straightNumber = outcome.straightNumber,
                    multiplier = outcome.multiplier,
                    payout = outcome.payout,
                    net = outcome.net,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.positiveOrNull(),
                    jackpotTierIndex = outcome.jackpotTierIndex.takeIf { it >= 0 },
                    jackpotTierPayoutPct = outcome.jackpotTierPayoutPct.takeIf { it > 0.0 },
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                )
            )

            is SpinOutcome.Lose -> ResponseEntity.ok(
                RouletteSpinResponse(
                    ok = true,
                    bet = outcome.bet.name,
                    betLabel = outcome.bet.display,
                    landed = outcome.landed,
                    color = outcome.color.name,
                    straightNumber = outcome.straightNumber,
                    multiplier = 0L,
                    payout = 0L,
                    net = -outcome.stake,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.positiveOrNull(),
                )
            )

            is SpinOutcome.InvalidNumber -> errors.badRequest(
                "Pick a number between ${outcome.min} and ${outcome.max} for a straight bet."
            )

            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }

    private fun parseBet(raw: String?): Roulette.Bet? =
        raw?.let { runCatching { Roulette.Bet.valueOf(it) }.getOrNull() }

    // Render-side projection of the bet menu. Exposed via Model so the
    // template doesn't have to re-derive payout labels — single source
    // of truth lives in Roulette.Bet.
    private fun betRows(): List<RouletteBetRow> = Roulette.Bet.entries.map { bet ->
        RouletteBetRow(
            id = bet.name,
            label = bet.display,
            multiplier = bet.multiplier,
            odds = "${bet.multiplier - 1}:1",
            requiresNumber = bet.requiresNumber,
        )
    }

    // Player-facing bet menu explainer. Lives next to the controller (vs.
    // hard-coded in the template) so a future bet-type addition only edits
    // one place. The "covers" copy is intentionally plain English — first-
    // time players shouldn't have to know roulette parlance.
    private fun payoutRows(): List<RoulettePayoutRow> = listOf(
        RoulettePayoutRow("Red / Black", "The 18 red or 18 black pockets. Zero loses.", "1:1"),
        RoulettePayoutRow("Odd / Even", "Odd or even between 1 and 36. Zero loses.", "1:1"),
        RoulettePayoutRow("Low / High", "1–18 or 19–36. Zero loses.", "1:1"),
        RoulettePayoutRow("Dozens", "1st 12, 2nd 12, or 3rd 12 — twelve numbers each.", "2:1"),
        RoulettePayoutRow(
            "Columns",
            "Column 1: 1·4·7… / Column 2: 2·5·8… / Column 3: 3·6·9… — twelve numbers each.",
            "2:1",
        ),
        RoulettePayoutRow("Straight", "A single number you pick (0–36).", "35:1"),
    )
}

data class RouletteSpinRequest(
    val stake: Long = 0,
    val bet: String? = null,
    val number: Int? = null,
    val autoTopUp: Boolean = false,
)

data class RouletteSpinResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val bet: String? = null,
    val betLabel: String? = null,
    val landed: Int? = null,
    val color: String? = null,
    val straightNumber: Int? = null,
    val multiplier: Long? = null,
    val payout: Long? = null,
    val net: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val jackpotTierIndex: Int? = null,
    val jackpotTierPayoutPct: Double? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike

data class RouletteBetRow(
    val id: String,
    val label: String,
    val multiplier: Long,
    val odds: String,
    val requiresNumber: Boolean,
)

data class RoulettePayoutRow(val label: String, val covers: String, val payout: String)
