package web.controller

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.service.BlackjackService
import database.service.BlackjackService.MultiActionOutcome
import database.service.BlackjackService.MultiCreateOutcome
import database.service.BlackjackService.MultiJoinOutcome
import database.service.BlackjackService.MultiLeaveOutcome
import database.service.BlackjackService.MultiStartOutcome
import database.service.BlackjackService.SoloActionOutcome
import database.service.BlackjackService.SoloDealOutcome
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
import web.service.BlackjackWebService
import web.service.EconomyWebService
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Web surface for `/blackjack`. Same [BlackjackService] the Discord
 * command uses, plus the same in-memory [BlackjackTableRegistry] — a
 * solo hand started in Discord can also be played from the web inbox,
 * and a multi table dealt anywhere shows up to all watchers.
 *
 * Pages:
 *   - `GET /blackjack/guilds` — guild selector
 *   - `GET /blackjack/{guildId}` — multi lobby (table list)
 *   - `GET /blackjack/{guildId}/solo` — solo hand page
 *   - `GET /blackjack/{guildId}/{tableId}` — multi table page
 *
 * JSON endpoints (browser polls every ~2s for live updates):
 *   - `GET  /blackjack/{guildId}/solo/state`
 *   - `GET  /blackjack/{guildId}/{tableId}/state`
 *   - `POST /blackjack/{guildId}/solo/deal`
 *   - `POST /blackjack/{guildId}/solo/action`
 *   - `POST /blackjack/{guildId}/create`
 *   - `POST /blackjack/{guildId}/{tableId}/join`
 *   - `POST /blackjack/{guildId}/{tableId}/start`
 *   - `POST /blackjack/{guildId}/{tableId}/leave`
 *   - `POST /blackjack/{guildId}/{tableId}/action`
 */
@Controller
@RequestMapping("/blackjack")
class BlackjackController(
    private val blackjackService: BlackjackService,
    private val blackjackWebService: BlackjackWebService,
    private val tableRegistry: BlackjackTableRegistry,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val jda: JDA,
) {

    /**
     * Shared mapper from auth/guard rejections (401 / 403) and casino-
     * style failure outcomes (insufficient credits / coins / invalid
     * stake / unknown user) into [BlackjackActionResponse]. Mirrors the
     * pattern HighlowController / SlotsController / etc. already use.
     */
    private val errors = CasinoOutcomeMapper { msg -> BlackjackActionResponse(ok = false, error = msg) }

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
        return "blackjack-guilds"
    }

    @GetMapping("/{guildId}")
    fun lobby(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/blackjack/guilds"
    ) { discordId ->
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/blackjack/guilds"
        }
        val profile = userService.getUserById(discordId, guildId)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", profile?.socialCredit ?: 0L)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("minAnte", Blackjack.MULTI_MIN_ANTE)
        model.addAttribute("maxAnte", Blackjack.MULTI_MAX_ANTE)
        model.addAttribute("maxSeats", Blackjack.MULTI_MAX_SEATS)
        model.addAttribute("tables", blackjackWebService.listMultiTables(guildId))
        model.addAttribute("username", user.displayName())
        "blackjack-lobby"
    }

    @GetMapping("/{guildId}/solo")
    fun soloPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/blackjack/guilds"
    ) { discordId ->
        val profile = userService.getUserById(discordId, guildId)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("balance", profile?.socialCredit ?: 0L)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("minStake", Blackjack.MIN_STAKE)
        model.addAttribute("maxStake", Blackjack.MAX_STAKE)
        model.addAttribute("username", user.displayName())
        model.addAttribute("myDiscordId", discordId.toString())
        "blackjack-solo"
    }

    @GetMapping("/{guildId}/{tableId}")
    fun tablePage(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/blackjack/guilds"
    ) { discordId ->
        val state = blackjackWebService.snapshot(tableId, discordId)
        if (state == null || state.guildId != guildId || state.mode != BlackjackTable.Mode.MULTI.name) {
            ra.addFlashAttribute("error", "That blackjack table no longer exists.")
            return@requireMemberForPage "redirect:/blackjack/$guildId"
        }
        val profile = userService.getUserById(discordId, guildId)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("tableId", tableId.toString())
        model.addAttribute("balance", profile?.socialCredit ?: 0L)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("ante", state.ante)
        model.addAttribute("username", user.displayName())
        model.addAttribute("myDiscordId", discordId.toString())
        "blackjack-table"
    }

    // -------------------------------------------------------------------------
    // SOLO JSON
    // -------------------------------------------------------------------------

    @GetMapping("/{guildId}/solo/state")
    @ResponseBody
    fun soloState(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackWebService.TableStateView> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        val tableId = findActiveSoloTable(guildId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        val view = blackjackWebService.snapshot(tableId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        ResponseEntity.ok(view)
    }

    @PostMapping("/{guildId}/solo/deal")
    @ResponseBody
    fun soloDeal(
        @PathVariable guildId: Long,
        @RequestBody request: BlackjackSoloDealRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = blackjackService.dealSolo(discordId, guildId, request.stake, request.autoTopUp)) {
            is SoloDealOutcome.Dealt -> ResponseEntity.ok(
                BlackjackActionResponse(
                    ok = true,
                    tableId = outcome.tableId,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )
            is SoloDealOutcome.Resolved -> ResponseEntity.ok(
                BlackjackActionResponse(
                    ok = true, tableId = outcome.tableId, resolved = true,
                    newBalance = outcome.newBalance,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    lossTribute = outcome.lossTribute.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )
            is SoloDealOutcome.InvalidStake -> errors.invalidStake(outcome.min, outcome.max)
            is SoloDealOutcome.InsufficientCredits -> errors.insufficientCredits(outcome.stake, outcome.have)
            is SoloDealOutcome.InsufficientCoinsForTopUp -> errors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            SoloDealOutcome.UnknownUser -> errors.unknownUser()
        }
    }

    @PostMapping("/{guildId}/solo/action")
    @ResponseBody
    fun soloAction(
        @PathVariable guildId: Long,
        @RequestBody request: BlackjackActionRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        val tableId = findActiveSoloTable(guildId, discordId)
            ?: return@requireMemberForJson ResponseEntity.status(404)
                .body(BlackjackActionResponse(false, error = "No active solo hand. Click Deal first."))
        val action = parseAction(request.action)
            ?: return@requireMemberForJson errors.badRequest("Unknown action: ${request.action}")

        when (val outcome = blackjackService.applySoloAction(discordId, guildId, tableId, action)) {
            is SoloActionOutcome.Continued -> ResponseEntity.ok(BlackjackActionResponse(ok = true, tableId = tableId))
            is SoloActionOutcome.Resolved -> {
                // Leave the resolved table in the registry so the JS poll can read the
                // final state (cards / dealer total / lastResult) and disable buttons.
                // [BlackjackService.dealSolo] sweeps it on the next deal; the idle
                // sweeper handles abandoned ones after [BlackjackTableRegistry.idleTtl].
                ResponseEntity.ok(
                    BlackjackActionResponse(
                        ok = true, tableId = tableId, resolved = true,
                        newBalance = outcome.newBalance,
                        jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                        lossTribute = outcome.lossTribute.takeIf { it > 0L }
                    )
                )
            }
            SoloActionOutcome.HandNotFound -> ResponseEntity.status(404)
                .body(BlackjackActionResponse(false, error = "Hand no longer exists."))
            SoloActionOutcome.NotYourHand -> ResponseEntity.status(403)
                .body(BlackjackActionResponse(false, error = "This isn't your hand."))
            SoloActionOutcome.IllegalAction -> errors.badRequest("You can't do that right now.")
            is SoloActionOutcome.InsufficientCreditsForDouble ->
                errors.insufficientCredits(outcome.needed, outcome.have)
            is SoloActionOutcome.InsufficientCreditsForSplit ->
                errors.insufficientCredits(outcome.needed, outcome.have)
        }
    }

    // -------------------------------------------------------------------------
    // MULTI JSON
    // -------------------------------------------------------------------------

    @GetMapping("/{guildId}/{tableId}/state")
    @ResponseBody
    fun tableState(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackWebService.TableStateView> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        val view = blackjackWebService.snapshot(tableId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        if (view.guildId != guildId) return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        ResponseEntity.ok(view)
    }

    @PostMapping("/{guildId}/create")
    @ResponseBody
    fun create(
        @PathVariable guildId: Long,
        @RequestBody request: BlackjackCreateRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = blackjackService.createMultiTable(discordId, guildId, request.ante, request.autoTopUp)) {
            is MultiCreateOutcome.Ok -> ResponseEntity.ok(
                BlackjackActionResponse(
                    ok = true,
                    tableId = outcome.tableId,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )
            is MultiCreateOutcome.InvalidAnte ->
                errors.badRequest("Ante must be between ${outcome.min} and ${outcome.max}.")
            is MultiCreateOutcome.InsufficientCredits -> errors.insufficientCredits(outcome.stake, outcome.have)
            is MultiCreateOutcome.InsufficientCoinsForTopUp -> errors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            MultiCreateOutcome.UnknownUser -> errors.unknownUser()
        }
    }

    @PostMapping("/{guildId}/{tableId}/join")
    @ResponseBody
    fun join(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @RequestBody(required = false) request: BlackjackJoinRequest? = null,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        val autoTopUp = request?.autoTopUp ?: false
        when (val outcome = blackjackService.joinMultiTable(discordId, guildId, tableId, autoTopUp)) {
            is MultiJoinOutcome.Ok -> ResponseEntity.ok(
                BlackjackActionResponse(
                    ok = true, tableId = tableId, newBalance = outcome.newBalance,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )
            MultiJoinOutcome.AlreadySeated -> errors.badRequest("You're already at this table.")
            MultiJoinOutcome.TableFull -> errors.badRequest("Table is full.")
            MultiJoinOutcome.TableNotFound -> ResponseEntity.status(404)
                .body(BlackjackActionResponse(false, error = "No such table."))
            MultiJoinOutcome.HandInProgress ->
                errors.badRequest("Wait for the current hand to end before joining.")
            is MultiJoinOutcome.InsufficientCredits -> errors.insufficientCredits(outcome.stake, outcome.have)
            is MultiJoinOutcome.InsufficientCoinsForTopUp -> errors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            MultiJoinOutcome.UnknownUser -> errors.unknownUser()
        }
    }

    @PostMapping("/{guildId}/{tableId}/start")
    @ResponseBody
    fun start(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = blackjackService.startMultiHand(discordId, guildId, tableId)) {
            is MultiStartOutcome.Ok -> ResponseEntity.ok(
                BlackjackActionResponse(ok = true, tableId = tableId, handNumber = outcome.handNumber)
            )
            MultiStartOutcome.NotEnoughPlayers ->
                errors.badRequest("Need at least ${Blackjack.MULTI_MIN_SEATS} seated players who can afford the ante.")
            MultiStartOutcome.HandAlreadyInProgress ->
                errors.badRequest("A hand is already in progress.")
            MultiStartOutcome.NotHost -> ResponseEntity.status(403)
                .body(BlackjackActionResponse(false, error = "Only the table host can deal hands."))
            MultiStartOutcome.TableNotFound -> ResponseEntity.status(404)
                .body(BlackjackActionResponse(false, error = "No such table."))
        }
    }

    @PostMapping("/{guildId}/{tableId}/leave")
    @ResponseBody
    fun leave(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        when (val outcome = blackjackService.leaveMultiTable(discordId, guildId, tableId)) {
            is MultiLeaveOutcome.Ok -> ResponseEntity.ok(
                BlackjackActionResponse(ok = true, tableId = tableId, refund = outcome.refund, newBalance = outcome.newBalance)
            )
            is MultiLeaveOutcome.QueuedForEndOfHand -> ResponseEntity.ok(
                BlackjackActionResponse(ok = true, tableId = tableId, refund = outcome.stakeHeld, queued = true)
            )
            MultiLeaveOutcome.AlreadyLeaving ->
                errors.badRequest("You've already asked to leave this hand.")
            MultiLeaveOutcome.NotSeated ->
                errors.badRequest("You're not seated at this table.")
            MultiLeaveOutcome.TableNotFound -> ResponseEntity.status(404)
                .body(BlackjackActionResponse(false, error = "No such table."))
        }
    }

    @PostMapping("/{guildId}/{tableId}/action")
    @ResponseBody
    fun multiAction(
        @PathVariable guildId: Long,
        @PathVariable tableId: Long,
        @RequestBody request: BlackjackActionRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<BlackjackActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = errors.errorBuilder
    ) { discordId ->
        val action = parseAction(request.action)
            ?: return@requireMemberForJson errors.badRequest("Unknown action: ${request.action}")

        when (val outcome = blackjackService.applyMultiAction(discordId, guildId, tableId, action)) {
            is MultiActionOutcome.Continued -> ResponseEntity.ok(BlackjackActionResponse(ok = true, tableId = tableId))
            is MultiActionOutcome.HandResolved -> ResponseEntity.ok(
                BlackjackActionResponse(ok = true, tableId = tableId, resolved = true, handNumber = outcome.result.handNumber)
            )
            MultiActionOutcome.NotYourTurn -> errors.badRequest("It isn't your turn yet.")
            MultiActionOutcome.NotSeated -> errors.badRequest("You aren't seated at this table.")
            MultiActionOutcome.NoHandInProgress -> errors.badRequest("No hand in progress on this table.")
            MultiActionOutcome.IllegalAction -> errors.badRequest("You can't do that right now.")
            MultiActionOutcome.TableNotFound -> ResponseEntity.status(404)
                .body(BlackjackActionResponse(false, error = "No such table."))
            is MultiActionOutcome.InsufficientCreditsForDouble ->
                errors.insufficientCredits(outcome.needed, outcome.have)
            is MultiActionOutcome.InsufficientCreditsForSplit ->
                errors.insufficientCredits(outcome.needed, outcome.have)
        }
    }

    private fun findActiveSoloTable(guildId: Long, discordId: Long): Long? {
        val mine = tableRegistry.listForGuild(guildId).filter { table ->
            table.mode == BlackjackTable.Mode.SOLO &&
                table.seats.any { it.discordId == discordId }
        }
        // Prefer a still-in-flight hand. After a hand resolves, the table now
        // sticks around (so /state can render the result + disable buttons)
        // until the next deal sweeps it — without this filter, an action POST
        // arriving right after a fresh deal could land on the stale RESOLVED
        // table instead of the new live one.
        return (mine.firstOrNull { it.phase != BlackjackTable.Phase.RESOLVED }
            ?: mine.firstOrNull())?.id
    }

    private fun parseAction(raw: String): Blackjack.Action? = when (raw.lowercase()) {
        "hit" -> Blackjack.Action.HIT
        "stand" -> Blackjack.Action.STAND
        "double" -> Blackjack.Action.DOUBLE
        "split" -> Blackjack.Action.SPLIT
        else -> null
    }

}

data class BlackjackSoloDealRequest(val stake: Long = 0, val autoTopUp: Boolean = false)
data class BlackjackCreateRequest(val ante: Long = 0, val autoTopUp: Boolean = false)
data class BlackjackJoinRequest(val autoTopUp: Boolean = false)
data class BlackjackActionRequest(val action: String = "")

data class BlackjackActionResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val tableId: Long? = null,
    val resolved: Boolean? = null,
    val handNumber: Long? = null,
    val newBalance: Long? = null,
    val refund: Long? = null,
    val queued: Boolean? = null,
    val jackpotPayout: Long? = null,
    val lossTribute: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
) : CasinoResponseLike
