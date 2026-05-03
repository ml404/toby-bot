package web.controller

import database.economy.Keno
import database.service.KenoService
import database.service.KenoService.PlayOutcome
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
 * Web surface for the `/keno` minigame. GET renders the 8×10 picker
 * grid + stake input + "Quick Pick N" auto-fill button; POST runs the
 * round via [KenoService.play] and returns JSON with the player's
 * picks, the 20 drawn numbers, hit count, multiplier, and net.
 *
 * Same shape as [BaccaratController] — keno is a one-shot wager so
 * there's no session state. The "draws light up one-by-one" reveal is
 * client-side via `keno.js` reading the body's `draws` array.
 *
 * Both surfaces (web + Discord) share [KenoService] so the paytable,
 * pool size, and balance debit/credit semantics stay consistent.
 */
@Controller
@RequestMapping("/casino/{guildId}/keno")
class KenoController(
    private val kenoService: KenoService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
) {

    private val errors = CasinoOutcomeMapper { msg -> KenoPlayResponse(false, msg) }

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
        model.addAttribute("minStake", Keno.MIN_STAKE)
        model.addAttribute("maxStake", Keno.MAX_STAKE)
        model.addAttribute("minSpots", Keno.MIN_SPOTS)
        model.addAttribute("maxSpots", Keno.MAX_SPOTS)
        model.addAttribute("poolSize", Keno.POOL_SIZE)
        model.addAttribute("draws", Keno.DRAWS)
        // Render the per-spots paytable as a list of {spots, payouts}
        // pairs so Thymeleaf can iterate with a `th:each` and the rules
        // section / a future paytable widget can read it without
        // hard-coding values that already live in the engine.
        model.addAttribute(
            "paytable",
            (Keno.MIN_SPOTS..Keno.MAX_SPOTS).map { spots ->
                mapOf(
                    "spots" to spots,
                    "payouts" to (Keno.PAYTABLE[spots] ?: emptyList())
                )
            }
        )
        "keno"
    }

    @PostMapping("/play")
    @ResponseBody
    fun play(
        @PathVariable guildId: Long,
        @RequestBody request: KenoPlayRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<KenoPlayResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = kenoService.play(
            discordId, guildId, request.stake, request.picks, request.autoTopUp
        )) {
            is PlayOutcome.Win -> ResponseEntity.ok(
                KenoPlayResponse(
                    ok = true,
                    picks = outcome.picks,
                    draws = outcome.draws,
                    hits = outcome.hits,
                    multiplier = outcome.multiplier,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                )
            )

            is PlayOutcome.Lose -> ResponseEntity.ok(
                KenoPlayResponse(
                    ok = true,
                    picks = outcome.picks,
                    draws = outcome.draws,
                    hits = outcome.hits,
                    multiplier = 0.0,
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.takeIf { it > 0L },
                )
            )

            is PlayOutcome.InsufficientCredits -> errors.insufficientCredits(outcome.stake, outcome.have)
            is PlayOutcome.InsufficientCoinsForTopUp -> errors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            is PlayOutcome.InvalidStake -> errors.invalidStake(outcome.min, outcome.max)
            is PlayOutcome.InvalidPicks -> errors.badRequest(
                "Pick ${outcome.min}-${outcome.max} distinct numbers between 1 and ${outcome.poolMax}."
            )
            PlayOutcome.UnknownUser -> errors.unknownUser()
        }
    }
}

data class KenoPlayRequest(
    val picks: List<Int> = emptyList(),
    val stake: Long = 0,
    val autoTopUp: Boolean = false
)

data class KenoPlayResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val picks: List<Int>? = null,
    val draws: List<Int>? = null,
    val hits: Int? = null,
    val multiplier: Double? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike
