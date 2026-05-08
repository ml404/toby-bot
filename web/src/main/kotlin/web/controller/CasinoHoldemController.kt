package web.controller

import common.casino.CasinoCommonFailure
import database.poker.CasinoHoldem
import database.poker.CasinoHoldemTableRegistry
import database.service.CasinoHoldemService
import database.service.CasinoHoldemService.ActionOutcome
import database.service.CasinoHoldemService.DealOutcome
import database.service.JackpotService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
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
import web.casino.CasinoResponseLike
import web.service.CasinoHoldemWebService
import web.service.EconomyWebService
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Web surface for `/casinoholdem`. Same [CasinoHoldemService] the
 * Discord command uses, plus the same in-memory
 * [CasinoHoldemTableRegistry] — a hand started in Discord can also be
 * played from the web inbox, and vice versa.
 *
 * Pages:
 *   - `GET /casinoholdem/guilds` — guild selector
 *   - `GET /casinoholdem/{guildId}` — solo play page
 *
 * JSON endpoints (browser polls every ~2s for live updates):
 *   - `GET  /casinoholdem/{guildId}/state`
 *   - `POST /casinoholdem/{guildId}/deal`
 *   - `POST /casinoholdem/{guildId}/action`
 */
@Controller
@RequestMapping("/casinoholdem")
class CasinoHoldemController(
    private val service: CasinoHoldemService,
    private val webService: CasinoHoldemWebService,
    private val tableRegistry: CasinoHoldemTableRegistry,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val jda: JDA,
) {

    private val errors = CasinoOutcomeMapper { msg -> CasinoHoldemActionResponse(ok = false, error = msg) }

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()
        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        return "casinoholdem-guilds"
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/casinoholdem/guilds"
    ) { discordId ->
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/casinoholdem/guilds"
        }
        val profile = userService.getUserById(discordId, guildId)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", profile?.socialCredit ?: 0L)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("jackpotWinPct", jackpotService.winProbabilityPct(guildId))
        model.addAttribute("minStake", CasinoHoldem.MIN_STAKE)
        model.addAttribute("maxStake", CasinoHoldem.MAX_STAKE)
        model.addAttribute("callMultiple", CasinoHoldem.CALL_MULTIPLE)
        model.addAttribute("username", user.displayName())
        model.addAttribute("myDiscordId", discordId.toString())
        "casinoholdem-solo"
    }

    @GetMapping("/{guildId}/state")
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

    @PostMapping("/{guildId}/deal")
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

    @PostMapping("/{guildId}/action")
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
    val lossTribute: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
) : CasinoResponseLike
