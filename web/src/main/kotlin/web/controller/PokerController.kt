package web.controller

import database.poker.PokerEngine
import database.poker.PokerTable
import database.service.PokerService
import database.service.PokerService.ActionOutcome
import database.service.PokerService.BuyInOutcome
import database.service.PokerService.CashOutOutcome
import database.service.JackpotService
import database.service.PokerService.CreateOutcome
import database.service.PokerService.StartHandOutcome
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
import web.service.EconomyWebService
import web.service.PokerWebService
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Web surface for /poker. Same [PokerService] the Discord command uses,
 * and the same in-memory [database.poker.PokerTableRegistry] — a hand
 * dealt in Discord can be played from the web inbox and vice versa.
 *
 * The viewer's own hole cards are projected by [PokerWebService] under
 * a server-side mask so other players' cards never leave the JVM in
 * the JSON snapshot. The browser polls `/poker/{guildId}/{tableId}/state`
 * every 2s for live updates.
 */
@Controller
@RequestMapping("/poker")
class PokerController(
    private val pokerService: PokerService,
    private val pokerWebService: PokerWebService,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val jda: JDA,
) {
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
        return "poker-guilds"
    }

    @GetMapping("/{guildId}")
    fun lobby(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/poker/guilds"
    ) { discordId ->
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/poker/guilds"
        }

        val profile = userService.getUserById(discordId, guildId)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", profile?.socialCredit ?: 0L)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("minBuyIn", PokerService.MIN_BUY_IN)
        model.addAttribute("maxBuyIn", PokerService.MAX_BUY_IN)
        model.addAttribute("smallBlind", PokerService.SMALL_BLIND)
        model.addAttribute("bigBlind", PokerService.BIG_BLIND)
        model.addAttribute("smallBet", PokerService.SMALL_BET)
        model.addAttribute("bigBet", PokerService.BIG_BET)
        model.addAttribute("maxSeats", PokerService.MAX_SEATS)
        model.addAttribute("tables", pokerWebService.listGuildTables(guildId))
        model.addAttribute("username", user.displayName())
        "poker-lobby"
    }

    @GetMapping("/{guildId}/{tableId}")
    fun tablePage(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/poker/guilds"
    ) { discordId ->
        if (pokerWebService.snapshot(tableId, discordId) == null) {
            ra.addFlashAttribute("error", "That table no longer exists.")
            return@requireMemberForPage "redirect:/poker/$guildId"
        }
        val profile = userService.getUserById(discordId, guildId)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("tableId", tableId.toString())
        model.addAttribute("balance", profile?.socialCredit ?: 0L)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("minBuyIn", PokerService.MIN_BUY_IN)
        model.addAttribute("maxBuyIn", PokerService.MAX_BUY_IN)
        model.addAttribute("username", user.displayName())
        model.addAttribute("myDiscordId", discordId.toString())
        "poker-table"
    }

    @GetMapping("/{guildId}/{tableId}/state")
    @ResponseBody
    fun state(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PokerWebService.TableStateView> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        val view = pokerWebService.snapshot(tableId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        if (view.guildId != guildId) {
            return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        }
        ResponseEntity.ok(view)
    }

    @PostMapping("/{guildId}/create")
    @ResponseBody
    fun create(
        @PathVariable guildId: Long,
        @RequestBody request: CreateRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<TableActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status -> tableErrorResponse(status) }
    ) { discordId ->
        when (val outcome = pokerService.createTable(discordId, guildId, request.buyIn, request.autoTopUp, request.free)) {
            is CreateOutcome.Ok -> ResponseEntity.ok(
                TableActionResponse(
                    ok = true,
                    tableId = outcome.tableId,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )
            is CreateOutcome.InvalidBuyIn -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Buy-in must be between ${outcome.min} and ${outcome.max}.")
            )
            is CreateOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Need ${outcome.needed} credits, you have ${outcome.have}.")
            )
            is CreateOutcome.InsufficientCoinsForTopUp -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Auto-topup needs ${outcome.needed} TOBY, you have ${outcome.have}.")
            )
            CreateOutcome.UnknownUser -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "No user record yet — try another TobyBot command first.")
            )
        }
    }

    @PostMapping("/{guildId}/{tableId}/join")
    @ResponseBody
    fun join(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @RequestBody request: JoinRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<TableActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status -> tableErrorResponse(status) }
    ) { discordId ->
        when (val outcome = pokerService.buyIn(discordId, guildId, tableId, request.buyIn, request.autoTopUp)) {
            is BuyInOutcome.Ok -> ResponseEntity.ok(
                TableActionResponse(
                    ok = true,
                    tableId = tableId,
                    newBalance = outcome.newBalance,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )
            BuyInOutcome.AlreadySeated -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "You're already at this table.")
            )
            BuyInOutcome.TableFull -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Table is full.")
            )
            BuyInOutcome.TableNotFound -> ResponseEntity.status(404).body(
                TableActionResponse(false, error = "No such table.")
            )
            is BuyInOutcome.InvalidBuyIn -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Buy-in must be between ${outcome.min} and ${outcome.max}.")
            )
            is BuyInOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Need ${outcome.needed} credits, you have ${outcome.have}.")
            )
            is BuyInOutcome.InsufficientCoinsForTopUp -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Auto-topup needs ${outcome.needed} TOBY, you have ${outcome.have}.")
            )
            BuyInOutcome.UnknownUser -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "No user record yet — try another TobyBot command first.")
            )
        }
    }

    @PostMapping("/{guildId}/{tableId}/start")
    @ResponseBody
    fun start(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<TableActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status -> tableErrorResponse(status) }
    ) { discordId ->
        when (val outcome = pokerService.startHand(discordId, guildId, tableId)) {
            is StartHandOutcome.Ok -> ResponseEntity.ok(TableActionResponse(ok = true, handNumber = outcome.handNumber))
            StartHandOutcome.HandAlreadyInProgress -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "A hand is already in progress.")
            )
            StartHandOutcome.NotEnoughPlayers -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Need at least 2 seated players with chips.")
            )
            StartHandOutcome.NotHost -> ResponseEntity.status(403).body(
                TableActionResponse(false, error = "Only the table host can deal hands.")
            )
            StartHandOutcome.TableNotFound -> ResponseEntity.status(404).body(
                TableActionResponse(false, error = "No such table.")
            )
        }
    }

    @PostMapping("/{guildId}/{tableId}/action")
    @ResponseBody
    fun action(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @RequestBody request: ActionRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<TableActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status -> tableErrorResponse(status) }
    ) { discordId ->
        val pokerAction = parseAction(request.action, tableId, discordId)
            ?: return@requireMemberForJson ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Unknown or illegal action: ${request.action}")
            )

        when (val outcome = pokerService.applyAction(discordId, guildId, tableId, pokerAction)) {
            ActionOutcome.Continued -> ResponseEntity.ok(TableActionResponse(ok = true))
            is ActionOutcome.StreetAdvanced -> ResponseEntity.ok(
                TableActionResponse(ok = true, phase = outcome.phase.name)
            )
            is ActionOutcome.HandResolved -> ResponseEntity.ok(
                TableActionResponse(
                    ok = true,
                    handNumber = outcome.result.handNumber,
                    pot = outcome.result.pot,
                    rake = outcome.result.rake
                )
            )
            ActionOutcome.TableNotFound -> ResponseEntity.status(404).body(
                TableActionResponse(false, error = "No such table.")
            )
            is ActionOutcome.Rejected -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = describeRejection(outcome.reason))
            )
        }
    }

    @PostMapping("/{guildId}/{tableId}/cashout")
    @ResponseBody
    fun cashOut(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<TableActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status -> tableErrorResponse(status) }
    ) { discordId ->
        when (val outcome = pokerService.cashOut(discordId, guildId, tableId)) {
            is CashOutOutcome.Ok -> ResponseEntity.ok(
                TableActionResponse(ok = true, chipsReturned = outcome.chipsReturned, newBalance = outcome.newBalance)
            )
            is CashOutOutcome.QueuedForEndOfHand -> ResponseEntity.ok(
                TableActionResponse(ok = true, chipsReturned = outcome.chipsHeld, queued = true)
            )
            CashOutOutcome.AlreadyLeaving -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "You've already asked to leave this hand.")
            )
            CashOutOutcome.HandInProgress -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "Hand in progress — fold first if you don't want to play it out.")
            )
            CashOutOutcome.NotSeated -> ResponseEntity.badRequest().body(
                TableActionResponse(false, error = "You're not seated at this table.")
            )
            CashOutOutcome.TableNotFound -> ResponseEntity.status(404).body(
                TableActionResponse(false, error = "No such table.")
            )
        }
    }

    private fun parseAction(raw: String, tableId: Long, discordId: Long): PokerEngine.PokerAction? {
        return when (raw.lowercase()) {
            "fold" -> PokerEngine.PokerAction.Fold
            "raise" -> PokerEngine.PokerAction.Raise
            "check" -> PokerEngine.PokerAction.Check
            "call" -> PokerEngine.PokerAction.Call
            // "checkcall" auto-picks based on what the seat owes — saves the
            // client from re-deriving the table state on every click.
            "checkcall", "check_call" -> {
                val table: PokerTable = pokerService.snapshot(tableId) ?: return null
                synchronized(table) {
                    val seat = table.seats.firstOrNull { it.discordId == discordId } ?: return@synchronized null
                    if (table.currentBet > seat.committedThisRound) PokerEngine.PokerAction.Call
                    else PokerEngine.PokerAction.Check
                }
            }
            else -> null
        }
    }

    /**
     * Single source of truth for the auth/membership 401/403 envelopes
     * every POST endpoint shares. Wraps the typed [TableActionResponse]
     * shape so callers don't have to duplicate the body.
     */
    private fun tableErrorResponse(status: Int): ResponseEntity<TableActionResponse> =
        ResponseEntity.status(status).body(
            TableActionResponse(
                false,
                error = if (status == 401) "Not signed in." else "You are not a member of that server."
            )
        )

    private fun describeRejection(reason: PokerEngine.RejectReason): String = when (reason) {
        PokerEngine.RejectReason.NO_HAND_IN_PROGRESS -> "No hand in progress."
        PokerEngine.RejectReason.NOT_AT_TABLE -> "You aren't seated at this table."
        PokerEngine.RejectReason.NOT_YOUR_TURN -> "It isn't your turn."
        PokerEngine.RejectReason.ILLEGAL_CHECK -> "You can't check — there's a bet to call."
        PokerEngine.RejectReason.ILLEGAL_CALL -> "Nothing to call."
        PokerEngine.RejectReason.ILLEGAL_RAISE -> "You can't raise here."
        PokerEngine.RejectReason.RAISE_CAP_REACHED -> "Raise cap reached for this street."
        PokerEngine.RejectReason.INSUFFICIENT_CHIPS_TO_CALL -> "Not enough chips to call — fold instead."
        PokerEngine.RejectReason.INSUFFICIENT_CHIPS_TO_RAISE -> "Not enough chips to raise — try Call."
    }
}

data class CreateRequest(val buyIn: Long = 0, val autoTopUp: Boolean = false, val free: Boolean = false)
data class JoinRequest(val buyIn: Long = 0, val autoTopUp: Boolean = false)
data class ActionRequest(val action: String = "")

data class TableActionResponse(
    val ok: Boolean,
    val error: String? = null,
    val tableId: Long? = null,
    val newBalance: Long? = null,
    val handNumber: Long? = null,
    val phase: String? = null,
    val pot: Long? = null,
    val rake: Long? = null,
    val chipsReturned: Long? = null,
    /**
     * v2-3: set when /poker leave is queued mid-hand. The seat hasn't
     * actually been cashed out yet — the engine will auto-fold the
     * leaver, refund chips, and remove the seat once the hand resolves.
     * Frontend uses this to render "leaving — chips return at end of
     * hand" instead of the regular cash-out toast.
     */
    val queued: Boolean? = null,
    /**
     * v2-4: set when an autoTopUp create / join sold TOBY to cover the
     * buy-in shortfall. Null when no sell happened (caller didn't ask
     * for autoTopUp, or balance already covered the buy-in).
     */
    val soldTobyCoins: Long? = null,
    /** v2-4: post-sell market price, paired with [soldTobyCoins]. */
    val newPrice: Double? = null,
)
