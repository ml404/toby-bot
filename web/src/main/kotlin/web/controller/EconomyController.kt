package web.controller

import common.economy.Coin
import database.dto.economy.UserPriceTriggerDto
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.CreateWatchResult
import web.service.EconomyWebService
import database.service.economy.EconomyTradeService.TradeOutcome
import database.service.social.SocialCreditAwardService
import web.service.PricePoint
import web.service.TradeMarker
import web.service.WatchView
import web.controller.support.GuildPickerSupport
import web.util.DefaultGuildCookie
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
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val defaultGuildId = DefaultGuildCookie.read(request)
        GuildPickerSupport.resolveRedirect(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        ) { "/economy/$it" }?.let { return it }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "economy-guilds"
    }

    @GetMapping("/{guildId}")
    fun economyPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
        @RequestParam(required = false) coin: String? = null
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/economy/guilds"
    ) { discordId ->
        val view = economyWebService.getEconomyView(guildId, discordId, Coin.fromSymbol(coin)) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/economy/guilds"
        }

        model.addAttribute("view", view)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("username", user.displayName())
        "economy"
    }

    @GetMapping("/portfolio")
    fun portfolioPicker(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val defaultGuildId = DefaultGuildCookie.read(request)
        GuildPickerSupport.resolveRedirect(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        ) { "/economy/$it/portfolio" }?.let { return it }

        // Zero or many guilds with no default — fall back to the market guild
        // list so the user can pick which server's portfolio to view.
        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "economy-guilds"
    }

    @GetMapping("/{guildId}/portfolio")
    fun portfolioPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/economy/guilds"
    ) { discordId ->
        val view = economyWebService.getPortfolio(guildId, discordId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/economy/guilds"
        }
        model.addAttribute("portfolio", view)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("username", user.displayName())
        "economy-portfolio"
    }

    @GetMapping("/{guildId}/history")
    @ResponseBody
    fun history(
        @PathVariable guildId: Long,
        @RequestParam(defaultValue = "1d") window: String,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) coin: String? = null
    ): ResponseEntity<HistoryResponse> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { _ ->
        val c = Coin.fromSymbol(coin)
        ResponseEntity.ok(
            HistoryResponse(
                points = economyWebService.getHistory(guildId, window, c),
                trades = economyWebService.getTrades(guildId, window, c)
            )
        )
    }

    @PostMapping("/{guildId}/buy")
    @ResponseBody
    fun buy(
        @PathVariable guildId: Long,
        @RequestBody request: TradeRequest,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) coin: String? = null
    ): ResponseEntity<TradeResponse> = trade(user, guildId, request, Coin.fromSymbol(coin)) { discordId ->
        economyWebService.buy(discordId, guildId, request.amount, Coin.fromSymbol(coin))
    }

    @PostMapping("/{guildId}/sell")
    @ResponseBody
    fun sell(
        @PathVariable guildId: Long,
        @RequestBody request: TradeRequest,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) coin: String? = null
    ): ResponseEntity<TradeResponse> = trade(user, guildId, request, Coin.fromSymbol(coin)) { discordId ->
        economyWebService.sell(discordId, guildId, request.amount, Coin.fromSymbol(coin))
    }

    @GetMapping("/{guildId}/watches")
    @ResponseBody
    fun listWatches(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) coin: String? = null
    ): ResponseEntity<WatchesListResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                WatchesListResponse(
                    ok = false,
                    error = if (status == 401) "Not signed in." else "You are not a member of that server."
                )
            )
        }
    ) { discordId ->
        val c = Coin.fromSymbol(coin)
        ResponseEntity.ok(
            WatchesListResponse(
                ok = true,
                watches = economyWebService.listWatches(discordId, guildId, c).map { WatchEntry.of(it) },
                price = economyWebService.currentPrice(guildId, c),
            )
        )
    }

    @PostMapping("/{guildId}/watches")
    @ResponseBody
    fun createWatch(
        @PathVariable guildId: Long,
        @RequestBody request: CreateWatchRequest,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) coin: String? = null
    ): ResponseEntity<CreateWatchResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                CreateWatchResponse(
                    ok = false,
                    error = if (status == 401) "Not signed in." else "You are not a member of that server."
                )
            )
        }
    ) { discordId ->
        if (request.side.isBlank() || request.amount == 0L || request.threshold == 0.0) {
            return@requireMemberForJson ResponseEntity.badRequest().body(
                CreateWatchResponse(ok = false, error = "Missing field.")
            )
        }
        val side = runCatching { UserPriceTriggerDto.Side.valueOf(request.side) }
            .getOrElse {
                return@requireMemberForJson ResponseEntity.badRequest().body(
                    CreateWatchResponse(ok = false, error = "Side must be BUY or SELL.")
                )
            }
        when (val outcome =
            economyWebService.createWatch(
                discordId, guildId, request.threshold, side, request.amount, Coin.fromSymbol(coin)
            )) {
            is CreateWatchResult.Ok -> ResponseEntity.ok(
                CreateWatchResponse(
                    ok = true,
                    watch = WatchEntry.of(outcome.watch),
                    notificationsAutoEnabled = outcome.notificationsAutoEnabled,
                )
            )
            is CreateWatchResult.ParityRejected -> ResponseEntity.badRequest().body(
                CreateWatchResponse(
                    ok = false,
                    error = "Threshold (${"%.4f".format(outcome.threshold)}) is essentially the " +
                            "current price (${"%.4f".format(outcome.currentPrice)}). Pick a target " +
                            "meaningfully above or below the current price so the direction is " +
                            "unambiguous."
                )
            )
            CreateWatchResult.InvalidAmount -> ResponseEntity.badRequest().body(
                CreateWatchResponse(ok = false, error = "Amount must be a positive number.")
            )
        }
    }

    @DeleteMapping("/{guildId}/watches/{watchId}")
    @ResponseBody
    fun deleteWatch(
        @PathVariable guildId: Long,
        @PathVariable watchId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<SimpleResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                SimpleResponse(
                    ok = false,
                    error = if (status == 401) "Not signed in." else "You are not a member of that server."
                )
            )
        }
    ) { discordId ->
        if (economyWebService.removeWatch(watchId, discordId)) {
            ResponseEntity.ok(SimpleResponse(ok = true))
        } else {
            ResponseEntity.status(404).body(
                SimpleResponse(ok = false, error = "No watch #$watchId found that you own.")
            )
        }
    }

    private fun trade(
        user: OAuth2User,
        guildId: Long,
        request: TradeRequest,
        coin: Coin,
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
                TradeResponse(false, "Need ${outcome.needed} ${coin.symbol}, you have ${outcome.have}.")
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

data class CreateWatchRequest(
    val threshold: Double = 0.0,
    val side: String = "",
    val amount: Long = 0,
)

data class WatchEntry(
    val id: Long,
    val side: String,
    val amount: Long,
    val threshold: Double,
    val priceAtCreation: Double,
    val enabled: Boolean,
    val firedAt: Long?,
    val createdAt: Long,
) {
    companion object {
        fun of(view: WatchView) = WatchEntry(
            id = view.id,
            side = view.side,
            amount = view.amount,
            threshold = view.thresholdPrice,
            priceAtCreation = view.priceAtCreation,
            enabled = view.enabled,
            firedAt = view.firedAt?.toEpochMilli(),
            createdAt = view.createdAt.toEpochMilli(),
        )
    }
}

data class WatchesListResponse(
    val ok: Boolean,
    val watches: List<WatchEntry> = emptyList(),
    val price: Double? = null,
    val error: String? = null,
)

data class CreateWatchResponse(
    val ok: Boolean,
    val error: String? = null,
    val watch: WatchEntry? = null,
    val notificationsAutoEnabled: Boolean = false,
)

data class SimpleResponse(val ok: Boolean, val error: String? = null)
