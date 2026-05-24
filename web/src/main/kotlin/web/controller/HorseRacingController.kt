package web.controller

import common.casino.CasinoCommonFailure
import common.casino.horseracing.HorseRacing
import database.service.casino.horseracing.HorseRacingService
import database.service.casino.horseracing.HorseRacingService.RaceOutcome
import database.service.economy.JackpotGame
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
 * Web surface for the `/horse-racing` minigame. GET renders the betting
 * form + the six-lane track for JS to animate; POST runs the race via
 * [HorseRacingService.race] and returns JSON with the finishing order
 * the JS uses to drive the lane animation.
 *
 * Both surfaces share [HorseRacingService] so Discord and web can't
 * drift on payout maths, debit/credit semantics, or stake bounds.
 */
@Controller
@RequestMapping("/casino/{guildId}/horse-racing")
class HorseRacingController(
    private val horseRacingService: HorseRacingService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
    private val stakeBounds: StakeBounds,
) {

    private val errors = CasinoOutcomeMapper { msg -> HorseRacingRaceResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra,
        template = "horse-racing", lobbyPath = "/casino/guilds",
        game = JackpotGame.HORSE_RACING,
    ) {
        val (minStake, maxStake) = stakeBounds.horseRacing(guildId)
        addAttribute("minStake", minStake)
        addAttribute("maxStake", maxStake)
        addAttribute("horses", horseRows())
        addAttribute("bets", betRows())
        addAttribute("fieldSize", HorseRacing.FIELD_SIZE)
    }

    @PostMapping("/race")
    @ResponseBody
    fun race(
        @PathVariable guildId: Long,
        @RequestBody request: HorseRacingRaceRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<HorseRacingRaceResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder,
    ) { discordId ->
        val bet = parseBet(request.bet)
            ?: return@requireMemberForJson errors.badRequest("Pick a bet from the list.")
        val horse = request.horse
            ?: return@requireMemberForJson errors.badRequest("Pick a horse.")

        val outcome = horseRacingService.race(
            discordId, guildId, request.stake, horse, bet, request.autoTopUp,
        )
        when (outcome) {
            is RaceOutcome.Win -> ResponseEntity.ok(
                HorseRacingRaceResponse(
                    ok = true,
                    bet = outcome.bet.name,
                    betLabel = outcome.bet.display,
                    pickedHorse = outcome.pickedHorse,
                    finishingOrder = outcome.finishingOrder,
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

            is RaceOutcome.Lose -> ResponseEntity.ok(
                HorseRacingRaceResponse(
                    ok = true,
                    bet = outcome.bet.name,
                    betLabel = outcome.bet.display,
                    pickedHorse = outcome.pickedHorse,
                    finishingOrder = outcome.finishingOrder,
                    multiplier = 0.0,
                    payout = 0L,
                    net = -outcome.stake,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.positiveOrNull(),
                )
            )

            is RaceOutcome.InvalidHorse -> errors.badRequest(
                "Pick a horse between ${outcome.min} and ${outcome.max}."
            )

            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }

    private fun parseBet(raw: String?): HorseRacing.Bet? =
        raw?.let { runCatching { HorseRacing.Bet.valueOf(it) }.getOrNull() }

    // Render-side projection of the field. Single source of truth lives
    // in HorseRacing.HORSES — the template just paints these rows.
    private fun horseRows(): List<HorseRacingHorseRow> = HorseRacing.HORSES.map { h ->
        HorseRacingHorseRow(
            index = h.index,
            name = h.name,
            emoji = h.emoji,
            winProbPct = (h.winProb * 100).toInt(),
            winMult = h.winMult,
            placeMult = h.placeMult,
            showMult = h.showMult,
        )
    }

    // Bet-type menu explainer for the rules panel. Lives next to the
    // controller so a future bet-type addition only edits one place.
    private fun betRows(): List<HorseRacingBetRow> = HorseRacing.Bet.entries.map { bet ->
        val pays = when (bet) {
            HorseRacing.Bet.WIN -> "1st only"
            HorseRacing.Bet.PLACE -> "1st or 2nd"
            HorseRacing.Bet.SHOW -> "1st, 2nd, or 3rd"
        }
        HorseRacingBetRow(id = bet.name, label = bet.display, pays = pays)
    }
}

data class HorseRacingRaceRequest(
    val stake: Long = 0,
    val bet: String? = null,
    val horse: Int? = null,
    val autoTopUp: Boolean = false,
)

data class HorseRacingRaceResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val bet: String? = null,
    val betLabel: String? = null,
    val pickedHorse: Int? = null,
    val finishingOrder: List<Int>? = null,
    val multiplier: Double? = null,
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

data class HorseRacingHorseRow(
    val index: Int,
    val name: String,
    val emoji: String,
    val winProbPct: Int,
    val winMult: Double,
    val placeMult: Double,
    val showMult: Double,
)

data class HorseRacingBetRow(val id: String, val label: String, val pays: String)
