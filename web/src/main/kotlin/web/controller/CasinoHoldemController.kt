package web.controller

import common.casino.CasinoCommonFailure
import database.poker.CasinoHoldem
import database.poker.CasinoHoldemTableRegistry
import database.service.CasinoHoldemService
import database.service.CasinoHoldemService.ActionOutcome
import database.service.CasinoHoldemService.DealOutcome
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
import web.service.CasinoHoldemWebService
import web.service.EconomyWebService
import web.util.WebGuildAccess

/**
 * Web surface for the per-guild Casino Hold'em page. Same
 * [CasinoHoldemService] the Discord command uses, plus the same
 * in-memory [CasinoHoldemTableRegistry] — a hand started in Discord can
 * also be played from the web, and vice versa.
 *
 * URL surface mirrors the rest of the casino minigame family
 * (`/casino/{guildId}/<game>`), routed via [CasinoPageContext.
 * renderMinigamePage] so the page model picks up `tobyCoins` /
 * `marketPrice` / `jackpotPool` for free — same wiring as Baccarat
 * et al. The dedicated `/casinoholdem/guilds` picker was retired in
 * favour of the shared [CasinoController] hub at `/casino/guilds`,
 * which already lists Hold'em alongside the other table games.
 *
 * Pages:
 *   - `GET /casino/{guildId}/casinoholdem` — solo play page
 *
 * JSON endpoints (browser polls every ~2s for live updates):
 *   - `GET  /casino/{guildId}/casinoholdem/state`
 *   - `POST /casino/{guildId}/casinoholdem/deal`
 *   - `POST /casino/{guildId}/casinoholdem/action`
 */
@Controller
@RequestMapping("/casino/{guildId}/casinoholdem")
class CasinoHoldemController(
    private val service: CasinoHoldemService,
    private val webService: CasinoHoldemWebService,
    private val tableRegistry: CasinoHoldemTableRegistry,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
    private val stakeBounds: StakeBounds,
) {

    private val errors = CasinoOutcomeMapper { msg -> CasinoHoldemActionResponse(ok = false, error = msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = pageContext.renderMinigamePage(
        user, guildId, economyWebService, model, ra,
        template = "casinoholdem-solo",
        game = JackpotGame.HOLDEM,
    ) {
        val discordId = user.attributes["id"].toString().toLong()
        val (minStake, maxStake) = stakeBounds.casinoHoldem(guildId)
        addAttribute("minStake", minStake)
        addAttribute("maxStake", maxStake)
        addAttribute("callMultiple", CasinoHoldem.CALL_MULTIPLE)
        addAttribute("myDiscordId", discordId.toString())
    }

    @GetMapping("/state")
    @ResponseBody
    fun state(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<CasinoHoldemWebService.TableStateView> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        val tableId = webService.findActiveTable(guildId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        val view = webService.snapshot(tableId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        if (view.guildId != guildId) return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        ResponseEntity.ok(view)
    }

    @PostMapping("/deal")
    @ResponseBody
    fun deal(
        @PathVariable guildId: Long,
        @RequestBody request: CasinoHoldemDealRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<CasinoHoldemActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = service.dealSolo(discordId, guildId, request.stake, request.autoTopUp)) {
            is DealOutcome.Dealt -> ResponseEntity.ok(
                CasinoHoldemActionResponse(
                    ok = true,
                    tableId = outcome.tableId,
                    newBalance = outcome.newBalance,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                )
            )
            is CasinoCommonFailure -> errors.mapCommonFailure(outcome)
        }
    }

    @PostMapping("/action")
    @ResponseBody
    fun action(
        @PathVariable guildId: Long,
        @RequestBody request: CasinoHoldemActionRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<CasinoHoldemActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        val tableId = webService.findActiveTable(guildId, discordId)
            ?: return@requireMemberForJson ResponseEntity.status(404)
                .body(CasinoHoldemActionResponse(false, error = "No active hand. Click Deal first."))
        val action = parseAction(request.action)
            ?: return@requireMemberForJson errors.badRequest("Unknown action: ${request.action}")

        when (val outcome = service.applyAction(discordId, guildId, tableId, action)) {
            is ActionOutcome.Resolved -> {
                // Leave the resolved table in registry so the JS poll can read
                // the final state and disable buttons. The next deal sweeps it.
                ResponseEntity.ok(
                    CasinoHoldemActionResponse(
                        ok = true,
                        tableId = tableId,
                        resolved = true,
                        newBalance = outcome.newBalance,
                        jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                        jackpotTierIndex = outcome.jackpotTierIndex.takeIf { it >= 0 },
                        jackpotTierPayoutPct = outcome.jackpotTierPayoutPct.takeIf { it > 0.0 },
                        lossTribute = outcome.lossTribute.takeIf { it > 0L },
                    )
                )
            }
            is ActionOutcome.InsufficientCreditsForCall ->
                errors.insufficientCredits(outcome.needed, outcome.have)
            ActionOutcome.HandNotFound -> ResponseEntity.status(404)
                .body(CasinoHoldemActionResponse(false, error = "Hand no longer exists."))
            ActionOutcome.NotYourHand -> ResponseEntity.status(403)
                .body(CasinoHoldemActionResponse(false, error = "This isn't your hand."))
            ActionOutcome.IllegalAction ->
                errors.badRequest("You can't do that right now.")
        }
    }

    private fun parseAction(raw: String): CasinoHoldem.Action? = when (raw.lowercase()) {
        "call" -> CasinoHoldem.Action.CALL
        "fold" -> CasinoHoldem.Action.FOLD
        else -> null
    }
}

data class CasinoHoldemDealRequest(val stake: Long = 0, val autoTopUp: Boolean = false)
data class CasinoHoldemActionRequest(val action: String = "")

data class CasinoHoldemActionResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val tableId: Long? = null,
    val resolved: Boolean? = null,
    val newBalance: Long? = null,
    val jackpotPayout: Long? = null,
    val jackpotTierIndex: Int? = null,
    val jackpotTierPayoutPct: Double? = null,
    val lossTribute: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
) : CasinoResponseLike
