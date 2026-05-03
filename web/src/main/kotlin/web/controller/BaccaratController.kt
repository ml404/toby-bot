package web.controller

import common.casino.CasinoCommonFailure
import database.economy.Baccarat
import database.service.BaccaratService
import database.service.BaccaratService.PlayOutcome
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

/**
 * Web surface for the `/baccarat` minigame. GET renders the picker UI
 * (Player / Banker / Tie + stake input + autoTopUp); POST runs the hand
 * via [BaccaratService.play] and returns JSON with both hands + verdict.
 *
 * Same shape as [CoinflipController] — baccarat is a one-shot wager so
 * there's no session state, no anchor-then-resolve split. The card-game
 * polish comes from rendering both hands client-side via the shared
 * `casino-render.js` glyph engine.
 *
 * Both surfaces share [BaccaratService] so Discord and web can't drift
 * on payout maths, third-card tableau, or balance debit/credit semantics.
 */
@Controller
@RequestMapping("/casino/{guildId}/baccarat")
class BaccaratController(
    private val baccaratService: BaccaratService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
) {

    private val errors = CasinoOutcomeMapper { msg -> BaccaratPlayResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra, template = "baccarat"
    ) {
        addAttribute("minStake", Baccarat.MIN_STAKE)
        addAttribute("maxStake", Baccarat.MAX_STAKE)
        addAttribute("playerMultiplier", Baccarat.PLAYER_WIN_MULT)
        addAttribute("bankerMultiplier", Baccarat.BANKER_WIN_MULT)
        addAttribute("tieMultiplier", Baccarat.TIE_WIN_MULT)
    }

    @PostMapping("/play")
    @ResponseBody
    fun play(
        @PathVariable guildId: Long,
        @RequestBody request: BaccaratPlayRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<BaccaratPlayResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        val side = parseSide(request.side)
            ?: return@requireMemberForJson errors.badRequest("Pick a side: PLAYER, BANKER, or TIE.")

        when (val outcome = baccaratService.play(discordId, guildId, request.stake, side, request.autoTopUp)) {
            is PlayOutcome.Win -> ResponseEntity.ok(
                BaccaratPlayResponse(
                    ok = true,
                    side = outcome.side.name,
                    winner = outcome.winner.name,
                    playerCards = outcome.playerCards.map { it.toString() },
                    bankerCards = outcome.bankerCards.map { it.toString() },
                    playerTotal = outcome.playerTotal,
                    bankerTotal = outcome.bankerTotal,
                    isPlayerNatural = outcome.isPlayerNatural,
                    isBankerNatural = outcome.isBankerNatural,
                    multiplier = outcome.multiplier,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    push = false,
                    jackpotPayout = outcome.jackpotPayout.positiveOrNull(),
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                )
            )

            is PlayOutcome.Push -> ResponseEntity.ok(
                BaccaratPlayResponse(
                    ok = true,
                    side = outcome.side.name,
                    winner = "TIE",
                    playerCards = outcome.playerCards.map { it.toString() },
                    bankerCards = outcome.bankerCards.map { it.toString() },
                    playerTotal = outcome.playerTotal,
                    bankerTotal = outcome.bankerTotal,
                    isPlayerNatural = outcome.isPlayerNatural,
                    isBankerNatural = outcome.isBankerNatural,
                    multiplier = Baccarat.PUSH_MULT,
                    net = 0L,
                    payout = outcome.stake,
                    newBalance = outcome.newBalance,
                    win = false,
                    push = true,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                )
            )

            is PlayOutcome.Lose -> ResponseEntity.ok(
                BaccaratPlayResponse(
                    ok = true,
                    side = outcome.side.name,
                    winner = outcome.winner.name,
                    playerCards = outcome.playerCards.map { it.toString() },
                    bankerCards = outcome.bankerCards.map { it.toString() },
                    playerTotal = outcome.playerTotal,
                    bankerTotal = outcome.bankerTotal,
                    isPlayerNatural = outcome.isPlayerNatural,
                    isBankerNatural = outcome.isBankerNatural,
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    push = false,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.positiveOrNull(),
                )
            )

            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }

    private fun parseSide(raw: String?): Baccarat.Side? = when (raw?.uppercase()) {
        "PLAYER" -> Baccarat.Side.PLAYER
        "BANKER" -> Baccarat.Side.BANKER
        "TIE" -> Baccarat.Side.TIE
        else -> null
    }
}

data class BaccaratPlayRequest(
    val side: String = "",
    val stake: Long = 0,
    val autoTopUp: Boolean = false
)

data class BaccaratPlayResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val side: String? = null,
    val winner: String? = null,
    val playerCards: List<String>? = null,
    val bankerCards: List<String>? = null,
    val playerTotal: Int? = null,
    val bankerTotal: Int? = null,
    val isPlayerNatural: Boolean? = null,
    val isBankerNatural: Boolean? = null,
    val multiplier: Double? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val push: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike
