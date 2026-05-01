package web.controller

import database.economy.Dice
import database.service.DiceService
import database.service.DiceService.RollOutcome
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
) {

    private val errors = CasinoOutcomeMapper { msg -> RollResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/casino/guilds"
    ) { discordId ->
        pageContext.populate(model, guildId, discordId, user) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/casino/guilds"
        }
        model.addAttribute("minStake", Dice.MIN_STAKE)
        model.addAttribute("maxStake", Dice.MAX_STAKE)
        model.addAttribute("sides", Dice.DEFAULT_SIDES)
        model.addAttribute("multiplier", Dice.DEFAULT_MULTIPLIER)
        "dice"
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
        when (val outcome = diceService.roll(discordId, guildId, request.stake, request.prediction, request.autoTopUp)) {
            is RollOutcome.Win -> ResponseEntity.ok(
                RollResponse(
                    ok = true,
                    landed = outcome.landed,
                    predicted = outcome.predicted,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
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
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.takeIf { it > 0L }
                )
            )

            is RollOutcome.InsufficientCredits -> errors.insufficientCredits(outcome.stake, outcome.have)
            is RollOutcome.InsufficientCoinsForTopUp -> errors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            is RollOutcome.InvalidStake -> errors.invalidStake(outcome.min, outcome.max)
            is RollOutcome.InvalidPrediction ->
                errors.badRequest("Pick a number between ${outcome.min} and ${outcome.max}.")

            RollOutcome.UnknownUser -> errors.unknownUser()
        }
    }
}

data class RollRequest(val prediction: Int = 0, val stake: Long = 0, val autoTopUp: Boolean = false)

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
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null
) : CasinoResponseLike
