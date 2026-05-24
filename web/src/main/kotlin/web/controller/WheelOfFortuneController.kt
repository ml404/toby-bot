package web.controller

import common.casino.CasinoCommonFailure
import common.economy.WheelOfFortune
import database.service.economy.JackpotGame
import database.service.casino.wheeloffortune.WheelOfFortuneService
import database.service.casino.wheeloffortune.WheelOfFortuneService.SpinOutcome
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
 * Web surface for the `/wheel` minigame. GET renders the picker UI;
 * POST runs the spin via [WheelOfFortuneService.spin] and returns JSON.
 *
 * Both surfaces share [WheelOfFortuneService] so Discord and web can't
 * drift on payout maths or balance debit/credit semantics.
 */
@Controller
@RequestMapping("/casino/{guildId}/wheel")
class WheelOfFortuneController(
    private val wheelService: WheelOfFortuneService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
    private val stakeBounds: StakeBounds,
) {

    private val errors = CasinoOutcomeMapper { msg -> WheelSpinResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra,
        template = "wheel",
        game = JackpotGame.WHEEL_OF_FORTUNE,
    ) {
        val (minStake, maxStake) = stakeBounds.wheelOfFortune(guildId)
        val slotCount = WheelOfFortune.DEFAULT_WEIGHTS.values.sum()
        addAttribute("minStake", minStake)
        addAttribute("maxStake", maxStake)
        addAttribute("picks", WheelOfFortune.PICKS)
        addAttribute("slotCount", slotCount)
        // Map of multiplier → slot count for the rules panel. Sorted
        // by multiplier so the template can iterate without re-sorting.
        addAttribute(
            "wheelWeights",
            WheelOfFortune.DEFAULT_WEIGHTS.entries.sortedBy { it.key }
                .map { mapOf("multiplier" to it.key, "slots" to it.value) }
        )
    }

    @PostMapping("/spin")
    @ResponseBody
    fun spin(
        @PathVariable guildId: Long,
        @RequestBody request: WheelSpinRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<WheelSpinResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = wheelService.spin(
            discordId, guildId, request.stake, request.pick, request.autoTopUp,
            clickX = request.clickX, clickY = request.clickY, mouseMoved = request.mouseMoved,
        )) {
            is SpinOutcome.Win -> ResponseEntity.ok(
                WheelSpinResponse(
                    ok = true,
                    pick = outcome.pickedMultiplier,
                    landed = outcome.landedMultiplier,
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
                WheelSpinResponse(
                    ok = true,
                    pick = outcome.pickedMultiplier,
                    landed = outcome.landedMultiplier,
                    payout = 0L,
                    net = -outcome.stake,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.positiveOrNull(),
                )
            )

            is SpinOutcome.InvalidPick ->
                errors.badRequest("Pick must be one of ${outcome.picks.joinToString { "${it}×" }}.")

            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }
}

// `clickX` / `clickY` / `mouseMoved` are bot-suspicion signals from
// `wheel.js`'s tracker. All three nullable so non-browser callers (Discord,
// keyboard submit) can omit them — backend treats nulls as non-suspicious.
data class WheelSpinRequest(
    val stake: Long = 0,
    val pick: Long = 0,
    val autoTopUp: Boolean = false,
    val clickX: Int? = null,
    val clickY: Int? = null,
    val mouseMoved: Boolean? = null,
)

data class WheelSpinResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val pick: Long? = null,
    val landed: Long? = null,
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
