package web.controller

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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.EconomyWebService
import database.service.EconomyTradeService.TradeOutcome
import database.service.SocialCreditAwardService
import web.service.PricePoint
import web.service.TradeMarker
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName

@Controller
@RequestMapping("/economy")
class EconomyController(
    private val economyWebService: EconomyWebService,
    private val awardService: SocialCreditAwardService
) {

    companion object {
        // Tiny participation award — the daily cap absorbs trade-spam abuse.
        const val UI_TRADE_CREDIT: Long = 1L
    }

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        return "economy-guilds"
    }

    @GetMapping("/{guildId}")
    fun economyPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/economy/guilds"
    ) { discordId ->
        val view = economyWebService.getEconomyView(guildId, discordId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/economy/guilds"
        }

        model.addAttribute("view", view)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("username", user.displayName())
        "economy"
    }

    @GetMapping("/{guildId}/history")
    @ResponseBody
    fun history(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "1d") window: String,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<HistoryResponse> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { _ ->
        ResponseEntity.ok(
            HistoryResponse(
                points = economyWebService.getHistory(guildId, window),
                trades = economyWebService.getTrades(guildId, window)
            )
        )
    }

    @PostMapping("/{guildId}/buy")
    @ResponseBody
    fun buy(
        @PathVariable guildId: Long,
        @RequestBody request: TradeRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<TradeResponse> = trade(user, guildId, request) { discordId ->
        economyWebService.buy(discordId, guildId, request.amount)
    }

    @PostMapping("/{guildId}/sell")
    @ResponseBody
    fun sell(
        @PathVariable guildId: Long,
        @RequestBody request: TradeRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<TradeResponse> = trade(user, guildId, request) { discordId ->
        economyWebService.sell(discordId, guildId, request.amount)
    }

    private fun trade(
        user: OAuth2User,
        guildId: Long,
        request: TradeRequest,
        action: (Long) -> TradeOutcome
    ): ResponseEntity<TradeResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                TradeResponse(
                    false,
                    if (status == 401) "Not signed in." else "You are not a member of that server."
                )
            )
        }
    ) { discordId ->
        if (request.amount <= 0) {
            return@requireMemberForJson ResponseEntity.badRequest().body(
                TradeResponse(false, "Amount must be positive.")
            )
        }
        when (val outcome = action(discordId)) {
            is TradeOutcome.Ok -> {
                val granted = awardService.award(
                    discordId = discordId,
                    guildId = guildId,
                    amount = UI_TRADE_CREDIT,
                    reason = "ui-trade"
                )
                ResponseEntity.ok(
                    TradeResponse(
                        ok = true,
                        error = null,
                        newCoins = outcome.newCoins,
                        newCredits = outcome.newCredits + granted,
                        newPrice = outcome.newPrice,
                        transactedCredits = outcome.transactedCredits,
                        fee = outcome.fee,
                    )
                )
            }
            is TradeOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                TradeResponse(false, "Need ${outcome.needed} credits, you have ${outcome.have}.")
            )
            is TradeOutcome.InsufficientCoins -> ResponseEntity.badRequest().body(
                TradeResponse(false, "Need ${outcome.needed} TOBY, you have ${outcome.have}.")
            )
            TradeOutcome.InvalidAmount -> ResponseEntity.badRequest().body(
                TradeResponse(false, "Amount must be a positive number.")
            )
            TradeOutcome.UnknownUser -> ResponseEntity.badRequest().body(
                TradeResponse(false, "No user record yet. Try another TobyBot command first.")
            )
        }
    }
}

data class TradeRequest(val amount: Long = 0)

data class TradeResponse(
    val ok: Boolean,
    val error: String? = null,
    val newCoins: Long? = null,
    val newCredits: Long? = null,
    val newPrice: Double? = null,
    val transactedCredits: Long? = null,
    val fee: Long? = null,
)

data class HistoryResponse(
    val points: List<PricePoint>,
    val trades: List<TradeMarker> = emptyList()
)
