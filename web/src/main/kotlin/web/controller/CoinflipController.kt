package web.controller

import database.economy.Coinflip
import database.service.CoinflipService
import database.service.CoinflipService.FlipOutcome
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
 * Web surface for the `/coinflip` minigame. GET renders the picker UI
 * (heads vs tails buttons + stake input); POST runs the flip via
 * [CoinflipService.flip] and returns JSON for the JS animation to settle
 * on.
 *
 * Both surfaces share [CoinflipService] so Discord and web can't drift
 * on payout maths or balance debit/credit semantics.
 */
@Controller
@RequestMapping("/casino/{guildId}/coinflip")
class CoinflipController(
    private val coinflipService: CoinflipService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
) {

    private val errors = CasinoOutcomeMapper { msg -> FlipResponse(false, msg) }

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
        model.addAttribute("minStake", Coinflip.MIN_STAKE)
        model.addAttribute("maxStake", Coinflip.MAX_STAKE)
        model.addAttribute("multiplier", Coinflip.DEFAULT_MULTIPLIER)
        "coinflip"
    }

    @PostMapping("/flip")
    @ResponseBody
    fun flip(
        @PathVariable guildId: Long,
        @RequestBody request: FlipRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<FlipResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        val side = parseSide(request.side)
            ?: return@requireMemberForJson errors.badRequest("Pick a side: HEADS or TAILS.")

        when (val outcome = coinflipService.flip(discordId, guildId, request.stake, side, request.autoTopUp)) {
            is FlipOutcome.Win -> ResponseEntity.ok(
                FlipResponse(
                    ok = true,
                    landed = outcome.landed.name,
                    predicted = outcome.predicted.name,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                )
            )

            is FlipOutcome.Lose -> ResponseEntity.ok(
                FlipResponse(
                    ok = true,
                    landed = outcome.landed.name,
                    predicted = outcome.predicted.name,
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.takeIf { it > 0L },
                )
            )

            is FlipOutcome.InsufficientCredits -> errors.insufficientCredits(outcome.stake, outcome.have)
            is FlipOutcome.InsufficientCoinsForTopUp -> errors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            is FlipOutcome.InvalidStake -> errors.invalidStake(outcome.min, outcome.max)
            FlipOutcome.UnknownUser -> errors.unknownUser()
        }
    }

    private fun parseSide(raw: String?): Coinflip.Side? = when (raw?.uppercase()) {
        "HEADS" -> Coinflip.Side.HEADS
        "TAILS" -> Coinflip.Side.TAILS
        else -> null
    }
}

data class FlipRequest(val side: String = "", val stake: Long = 0, val autoTopUp: Boolean = false)

data class FlipResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val landed: String? = null,
    val predicted: String? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike
