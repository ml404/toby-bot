package web.controller

import common.casino.CasinoCommonFailure
import database.economy.Dice
import database.service.DiceService
import database.service.DiceService.RollOutcome
import database.service.JackpotGame
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
 * Web surface for the `/dice` minigame. GET renders the picker UI;
 * POST runs the roll via [DiceService.roll] and returns JSON.
 *
 * Both surfaces share [DiceService] so Discord and web can't drift on
 * payout maths or balance debit/credit semantics.
 */
@Controller
@RequestMapping("/casino/{guildId}/dice")
class DiceController(
    private val diceService: DiceService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
    private val stakeBounds: StakeBounds,
) {

    private val errors = CasinoOutcomeMapper { msg -> RollResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra, template = "dice",
        game = JackpotGame.DICE,
    ) {
        val (minStake, maxStake) = stakeBounds.dice(guildId)
        addAttribute("minStake", minStake)
        addAttribute("maxStake", maxStake)
        addAttribute("sides", Dice.DEFAULT_SIDES)
        addAttribute("multiplier", Dice.DEFAULT_MULTIPLIER)
    }

    @PostMapping("/roll")
    @ResponseBody
    fun roll(
        @PathVariable guildId: Long,
        @RequestBody request: RollRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<RollResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = diceService.roll(
            discordId, guildId, request.stake, request.prediction, request.autoTopUp,
            clickX = request.clickX, clickY = request.clickY, mouseMoved = request.mouseMoved,
        )) {
            is RollOutcome.Win -> ResponseEntity.ok(
                RollResponse(
                    ok = true,
                    landed = outcome.landed,
                    predicted = outcome.predicted,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.positiveOrNull(),
                    jackpotTierIndex = outcome.jackpotTierIndex.takeIf { it >= 0 },
                    jackpotTierPayoutPct = outcome.jackpotTierPayoutPct.takeIf { it > 0.0 },
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                )
            )

            is RollOutcome.Lose -> ResponseEntity.ok(
                RollResponse(
                    ok = true,
                    landed = outcome.landed,
                    predicted = outcome.predicted,
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.positiveOrNull(),
                )
            )

            is RollOutcome.InvalidPrediction ->
                errors.badRequest("Pick a number between ${outcome.min} and ${outcome.max}.")

            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }
}

// `clickX` / `clickY` / `mouseMoved` are bot-suspicion signals from
// `dice.js`'s tracker. All three nullable so non-browser callers (Discord,
// keyboard submit) can omit them — backend treats nulls as non-suspicious.
data class RollRequest(
    val prediction: Int = 0,
    val stake: Long = 0,
    val autoTopUp: Boolean = false,
    val clickX: Int? = null,
    val clickY: Int? = null,
    val mouseMoved: Boolean? = null,
)

data class RollResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val landed: Int? = null,
    val predicted: Int? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val jackpotTierIndex: Int? = null,
    val jackpotTierPayoutPct: Double? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike
