package web.controller

import common.casino.CasinoCommonFailure
import database.economy.Plinko
import database.service.JackpotGame
import database.service.PlinkoService
import database.service.PlinkoService.DropOutcome
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
 * Web surface for the `/plinko` minigame. GET renders the picker UI
 * with the three risk profiles; POST runs the drop via [PlinkoService.drop]
 * and returns JSON for the JS animation to settle the ball.
 *
 * Both surfaces share [PlinkoService] so Discord and web can't drift on
 * payout maths, debit/credit semantics, or stake bounds.
 */
@Controller
@RequestMapping("/casino/{guildId}/plinko")
class PlinkoController(
    private val plinkoService: PlinkoService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
    private val stakeBounds: StakeBounds,
) {

    private val errors = CasinoOutcomeMapper { msg -> DropResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra,
        template = "plinko",
        game = JackpotGame.PLINKO,
    ) {
        val (minStake, maxStake) = stakeBounds.plinko(guildId)
        addAttribute("minStake", minStake)
        addAttribute("maxStake", maxStake)
        addAttribute("rows", Plinko.ROWS)
        addAttribute("buckets", Plinko.BUCKETS)
        addAttribute("risks", Plinko.Risk.entries.map { it.name })
        // Each profile's payout table, indexed by bucket left-to-right.
        // Template renders as a small badge row per risk.
        addAttribute(
            "payoutTables",
            Plinko.DEFAULT_PAYOUTS.mapValues { (_, arr) -> arr.toList() }
                .mapKeys { it.key.name }
        )
    }

    @PostMapping("/drop")
    @ResponseBody
    fun drop(
        @PathVariable guildId: Long,
        @RequestBody request: DropRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<DropResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        val risk = parseRisk(request.risk)
            ?: return@requireMemberForJson errors.badRequest(
                "Risk must be one of ${Plinko.Risk.entries.joinToString { it.name }}."
            )
        when (val outcome = plinkoService.drop(
            discordId, guildId, request.stake, risk, request.autoTopUp,
            clickX = request.clickX, clickY = request.clickY, mouseMoved = request.mouseMoved,
        )) {
            is DropOutcome.Win -> ResponseEntity.ok(
                DropResponse(
                    ok = true,
                    risk = outcome.risk.name,
                    bucket = outcome.bucket,
                    multiplier = outcome.multiplier,
                    payout = outcome.payout,
                    net = outcome.net,
                    newBalance = outcome.newBalance,
                    win = true,
                    push = false,
                    jackpotPayout = outcome.jackpotPayout.positiveOrNull(),
                    jackpotTierIndex = outcome.jackpotTierIndex.takeIf { it >= 0 },
                    jackpotTierPayoutPct = outcome.jackpotTierPayoutPct.takeIf { it > 0.0 },
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                )
            )

            is DropOutcome.Lose -> ResponseEntity.ok(
                DropResponse(
                    ok = true,
                    risk = outcome.risk.name,
                    bucket = outcome.bucket,
                    multiplier = outcome.multiplier,
                    payout = outcome.payout,
                    net = outcome.net,
                    newBalance = outcome.newBalance,
                    win = false,
                    push = false,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.positiveOrNull(),
                )
            )

            is DropOutcome.Push -> ResponseEntity.ok(
                DropResponse(
                    ok = true,
                    risk = outcome.risk.name,
                    bucket = outcome.bucket,
                    multiplier = 1.0,
                    payout = outcome.stake,
                    net = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    push = true,
                    soldTobyCoins = outcome.soldTobyCoins.positiveOrNull(),
                    newPrice = outcome.newPrice,
                )
            )

            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }

    private fun parseRisk(value: String?): Plinko.Risk? = value
        ?.trim()
        ?.uppercase()
        ?.let { name -> Plinko.Risk.entries.firstOrNull { it.name == name } }
}

// `clickX` / `clickY` / `mouseMoved` are bot-suspicion signals from
// `plinko.js`'s tracker. All three nullable so non-browser callers (Discord,
// keyboard submit) can omit them — backend treats nulls as non-suspicious.
data class DropRequest(
    val stake: Long = 0,
    val risk: String? = null,
    val autoTopUp: Boolean = false,
    val clickX: Int? = null,
    val clickY: Int? = null,
    val mouseMoved: Boolean? = null,
)

data class DropResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val risk: String? = null,
    val bucket: Int? = null,
    val multiplier: Double? = null,
    val payout: Long? = null,
    val net: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val push: Boolean? = null,
    val jackpotPayout: Long? = null,
    val jackpotTierIndex: Int? = null,
    val jackpotTierPayoutPct: Double? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike
