package web.controller

import common.connect4.Connect4Engine
import common.tictactoe.TicTacToeEngine
import database.boardgame.TurnBasedBoardWagerService
import database.connect4.Connect4SessionRegistry
import database.duel.PendingDuelRegistry
import database.rps.RpsSessionRegistry
import database.service.Connect4Service
import database.service.DuelService
import database.service.DuelService.AcceptOutcome
import database.service.DuelService.StartOutcome
import database.service.RpsService
import database.service.PvpWagerService
import database.service.TicTacToeService
import database.service.UserService
import database.tictactoe.TicTacToeSessionRegistry
import jakarta.servlet.http.HttpServletRequest
import net.dv8tion.jda.api.JDA
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.casino.StakeBounds
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.event.WebDuelOfferedEvent
import web.service.EconomyWebService
import web.service.PvpSseService
import web.service.PvpWebService
import web.service.MemberLookupHelper
import web.controller.support.GuildPickerSupport
import web.util.DefaultGuildCookie
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Web surface for player-vs-player matchups. Today the only fully
 * wired game is `/pvp/{guildId}/duel/...` — same [DuelService] the
 * Discord command uses and the same in-memory [PendingDuelRegistry]
 * so a duel offered in Discord can be accepted via the web inbox and
 * vice versa. Rock-paper-scissors plays end-to-end on the web via
 * `/pvp/{guildId}/rps/...`; tic-tac-toe and connect 4 tabs render in
 * the unified page but are placeholders until their controller
 * endpoints land in a follow-up PR.
 *
 * Old `/duel/...` URLs are kept alive via [DuelRedirectController]
 * which 301/308s every former route into the new `/pvp/...` space.
 */
@Controller
@RequestMapping("/pvp")
class PvpController(
    private val duelService: DuelService,
    private val rpsService: RpsService,
    private val rpsSessionRegistry: RpsSessionRegistry,
    private val ticTacToeService: TicTacToeService,
    private val ticTacToeSessionRegistry: TicTacToeSessionRegistry,
    private val connect4Service: Connect4Service,
    private val connect4SessionRegistry: Connect4SessionRegistry,
    private val pvpWebService: PvpWebService,
    private val pvpSseService: PvpSseService,
    private val pendingDuelRegistry: PendingDuelRegistry,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
    private val jda: JDA,
    private val eventPublisher: ApplicationEventPublisher,
    private val stakeBounds: StakeBounds,
    private val memberLookup: MemberLookupHelper,
) {
    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        @RequestParam(required = false) game: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val tab = sanitizeTabSlug(game)
        val tabQuery = if (tab != null) "?tab=$tab" else ""

        val defaultGuildId = DefaultGuildCookie.read(request)
        GuildPickerSupport.resolveRedirect(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        ) { "/pvp/$it$tabQuery" }?.let { return it }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("defaultGuildId", defaultGuildId)
        // Per-guild card links carry `?tab=<slug>` so picking a guild from the
        // navbar's PvP dropdown still deep-links to the right tab after the
        // round-trip through this picker page.
        model.addAttribute("tabQuery", tabQuery)
        return "pvp-guilds"
    }

    /** Whitelist accepted PvP tab slugs so a navbar-driven `?game=` query
     *  param can drive the in-page tab strip without opening a fuzzing
     *  vector. Anything outside the four valid slugs is dropped silently. */
    private fun sanitizeTabSlug(raw: String?): String? = when (raw?.lowercase()) {
        "duel", "rps", "tictactoe", "connect4" -> raw.lowercase()
        else -> null
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        // Both `?tab=` (the canonical query, used by guildList's redirect)
        // and `?game=` (the navbar entry shape) drive the initial tab.
        // Tab wins if both are present.
        @RequestParam(required = false) tab: String?,
        @RequestParam(required = false) game: String?,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/pvp/guilds"
    ) { discordId ->
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/pvp/guilds"
        }

        val profile = userService.getUserById(discordId, guildId)
        val balance = profile?.socialCredit ?: 0L
        val pending = pvpWebService.duelPendingForOpponent(discordId, guildId)
        val outgoing = pvpWebService.duelPendingForInitiator(discordId, guildId)
        val members = economyWebService.getGuildMembers(guildId).filter { it.id != discordId.toString() }

        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", balance)
        val (minStake, maxStake) = stakeBounds.duel(guildId)
        model.addAttribute("minStake", minStake)
        model.addAttribute("maxStake", maxStake)
        val (rpsMinStake, rpsMaxStake) = stakeBounds.rps(guildId)
        model.addAttribute("rpsMinStake", rpsMinStake)
        model.addAttribute("rpsMaxStake", rpsMaxStake)
        val (tttMinStake, tttMaxStake) = stakeBounds.ticTacToe(guildId)
        model.addAttribute("tttMinStake", tttMinStake)
        model.addAttribute("tttMaxStake", tttMaxStake)
        val (c4MinStake, c4MaxStake) = stakeBounds.connect4(guildId)
        model.addAttribute("c4MinStake", c4MinStake)
        model.addAttribute("c4MaxStake", c4MaxStake)
        model.addAttribute("pending", pending)
        model.addAttribute("outgoing", outgoing)
        model.addAttribute("ttlLabel", PendingDuelRegistry.formatTtl(pendingDuelRegistry.ttl))
        model.addAttribute("ttlSeconds", pendingDuelRegistry.ttl.seconds)
        model.addAttribute("members", members)
        // Plumb the current user's display info so the Preview-animation
        // button on /pvp can render the same Discord avatar + nickname
        // the inbox already shows for the initiator side.
        val me = memberLookup.resolve(guildId, discordId)
        model.addAttribute("currentUserId", discordId.toString())
        model.addAttribute("currentUserName", me?.name ?: memberLookup.fallbackName(discordId))
        model.addAttribute("currentUserAvatarUrl", me?.avatarUrl)
        // `?tab=` (post-redirect canonical) wins over `?game=` (navbar shape)
        // when both arrive together. Either way, pvp.js reads
        // `data-initial-tab` and flips to the matching tab on load.
        model.addAttribute("initialTab", sanitizeTabSlug(tab) ?: sanitizeTabSlug(game) ?: "")
        model.addAttribute("username", user.displayName())
        "pvp"
    }

    @GetMapping("/{guildId}/duel/pending")
    @ResponseBody
    fun pendingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.PendingDuelView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        ResponseEntity.ok(pvpWebService.duelPendingForOpponent(discordId, guildId))
    }

    @GetMapping("/{guildId}/duel/outgoing")
    @ResponseBody
    fun outgoingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpWebService.OutgoingPayload> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        ResponseEntity.ok(pvpWebService.duelOutgoingPayload(discordId, guildId))
    }

    @PostMapping("/{guildId}/duel/challenge")
    @ResponseBody
    fun challenge(
        @PathVariable guildId: Long,
        @RequestBody request: ChallengeRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<ChallengeResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                ChallengeResponse(
                    false,
                    error = if (status == 401) "Not signed in." else "You are not a member of that server."
                )
            )
        }
    ) { discordId ->
        val opponentDiscordId = request.opponentDiscordId.toLong()
        if (opponentDiscordId == discordId) {
            return@requireMemberForJson ResponseEntity.badRequest().body(
                ChallengeResponse(false, error = "You can't duel yourself.")
            )
        }
        if (!economyWebService.isMember(opponentDiscordId, guildId)) {
            return@requireMemberForJson ResponseEntity.badRequest().body(
                ChallengeResponse(false, error = "Pick someone from this server.")
            )
        }

        pvpWebService.ensureOpponent(opponentDiscordId, guildId)

        val start = duelService.startDuel(
            initiatorDiscordId = discordId,
            opponentDiscordId = opponentDiscordId,
            guildId = guildId,
            stake = request.stake
        )
        if (start !is StartOutcome.Ok) {
            return@requireMemberForJson ResponseEntity.badRequest().body(
                ChallengeResponse(false, error = startErrorMessage(start))
            )
        }

        val offer = pendingDuelRegistry.register(
            guildId = guildId,
            initiatorDiscordId = discordId,
            opponentDiscordId = opponentDiscordId,
            stake = request.stake
        )
        eventPublisher.publishEvent(
            WebDuelOfferedEvent(
                guildId = guildId,
                duelId = offer.id,
                initiatorDiscordId = discordId,
                opponentDiscordId = opponentDiscordId,
                stake = request.stake
            )
        )
        ResponseEntity.ok(
            ChallengeResponse(ok = true, duelId = offer.id, stake = request.stake)
        )
    }

    @PostMapping("/{guildId}/duel/{duelId}/accept")
    @ResponseBody
    fun accept(
        @PathVariable guildId: Long,
        @PathVariable duelId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<DuelActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(DuelActionResponse(false, error = "Not signed in."))

        val offer = pendingDuelRegistry.get(duelId)
            ?: return ResponseEntity.status(410).body(DuelActionResponse(false, error = "This offer already resolved or expired."))
        if (offer.guildId != guildId) {
            return ResponseEntity.badRequest().body(DuelActionResponse(false, error = "Wrong guild for this offer."))
        }
        if (offer.opponentDiscordId != discordId) {
            return ResponseEntity.status(403).body(DuelActionResponse(false, error = "This isn't your duel offer."))
        }

        // Atomically claim the offer; if we lose the race (timeout / Discord-side accept), 410.
        val claimed = pendingDuelRegistry.consumeForAccept(duelId)
            ?: return ResponseEntity.status(410).body(DuelActionResponse(false, error = "This offer already resolved or expired."))

        val outcome = duelService.acceptDuel(
            initiatorDiscordId = claimed.initiatorDiscordId,
            opponentDiscordId = claimed.opponentDiscordId,
            guildId = claimed.guildId,
            stake = claimed.stake
        )
        return when (outcome) {
            is AcceptOutcome.Win -> ResponseEntity.ok(
                DuelActionResponse(
                    ok = true,
                    winnerDiscordId = outcome.winnerDiscordId.toString(),
                    loserDiscordId = outcome.loserDiscordId.toString(),
                    stake = outcome.stake,
                    pot = outcome.pot,
                    winnerNewBalance = outcome.winnerNewBalance,
                    loserNewBalance = outcome.loserNewBalance,
                    lossTribute = outcome.lossTribute,
                )
            )
            is AcceptOutcome.InitiatorInsufficient -> ResponseEntity.badRequest().body(
                DuelActionResponse(false, error = "Challenger no longer has enough credits.")
            )
            is AcceptOutcome.OpponentInsufficient -> ResponseEntity.badRequest().body(
                DuelActionResponse(false, error = "You no longer have enough credits.")
            )
            AcceptOutcome.UnknownInitiator -> ResponseEntity.badRequest().body(
                DuelActionResponse(false, error = "Challenger's user record vanished.")
            )
            AcceptOutcome.UnknownOpponent -> ResponseEntity.badRequest().body(
                DuelActionResponse(false, error = "Your user record vanished.")
            )
        }
    }

    @PostMapping("/{guildId}/duel/{duelId}/decline")
    @ResponseBody
    fun decline(
        @PathVariable guildId: Long,
        @PathVariable duelId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<DuelActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(DuelActionResponse(false, error = "Not signed in."))

        val offer = pendingDuelRegistry.get(duelId)
            ?: return ResponseEntity.status(410).body(DuelActionResponse(false, error = "This offer already resolved or expired."))
        if (offer.guildId != guildId) {
            return ResponseEntity.badRequest().body(DuelActionResponse(false, error = "Wrong guild for this offer."))
        }
        if (offer.opponentDiscordId != discordId) {
            return ResponseEntity.status(403).body(DuelActionResponse(false, error = "This isn't your duel offer."))
        }
        pendingDuelRegistry.cancel(duelId)
            ?: return ResponseEntity.status(410).body(DuelActionResponse(false, error = "This offer already resolved or expired."))
        return ResponseEntity.ok(DuelActionResponse(ok = true))
    }

    @PostMapping("/{guildId}/duel/{duelId}/cancel")
    @ResponseBody
    fun cancel(
        @PathVariable guildId: Long,
        @PathVariable duelId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<DuelActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(DuelActionResponse(false, error = "Not signed in."))

        val offer = pendingDuelRegistry.get(duelId)
            ?: return ResponseEntity.status(410).body(DuelActionResponse(false, error = "This offer already resolved or expired."))
        if (offer.guildId != guildId) {
            return ResponseEntity.badRequest().body(DuelActionResponse(false, error = "Wrong guild for this offer."))
        }
        if (offer.initiatorDiscordId != discordId) {
            return ResponseEntity.status(403).body(DuelActionResponse(false, error = "This isn't your duel offer."))
        }
        pendingDuelRegistry.cancel(duelId)
            ?: return ResponseEntity.status(410).body(DuelActionResponse(false, error = "This offer already resolved or expired."))
        return ResponseEntity.ok(DuelActionResponse(ok = true))
    }

    private fun startErrorMessage(outcome: StartOutcome): String = when (outcome) {
        is StartOutcome.InvalidStake -> "Stake must be between ${outcome.min} and ${outcome.max} credits."
        is StartOutcome.InvalidOpponent -> when (outcome.reason) {
            StartOutcome.InvalidOpponent.Reason.SELF -> "You can't duel yourself."
            StartOutcome.InvalidOpponent.Reason.BOT -> "You can't duel a bot."
        }
        is StartOutcome.InitiatorInsufficient ->
            "You need ${outcome.needed} credits but only have ${outcome.have}."
        is StartOutcome.OpponentInsufficient ->
            "Opponent only has ${outcome.have} credits — they can't cover a ${outcome.needed} stake."
        StartOutcome.UnknownInitiator -> "No user record yet. Try another TobyBot command first."
        StartOutcome.UnknownOpponent -> "Opponent has no user record in this guild yet."
        is StartOutcome.Ok -> "OK"
    }

    // ─── RPS ──────────────────────────────────────────────────────────

    /**
     * SSE stream of every PvP event relevant to the signed-in viewer
     * in this guild — new offers, accepts, declines, picks,
     * resolutions. Client opens one EventSource per page; updates
     * appear sub-100ms after the originating mutation. Replaces what
     * a polling timer would do.
     */
    @GetMapping("/{guildId}/stream", produces = ["text/event-stream"])
    @ResponseBody
    fun pvpStream(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): SseEmitter {
        val discordId = user.discordIdOrNull()
            ?: return SseEmitter(0).also { it.completeWithError(IllegalStateException("Not signed in.")) }
        if (!economyWebService.isMember(discordId, guildId)) {
            return SseEmitter(0).also { it.completeWithError(IllegalStateException("Not a member of this server.")) }
        }
        return pvpSseService.register(guildId, discordId)
    }

    @GetMapping("/{guildId}/rps/pending")
    @ResponseBody
    fun rpsPendingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.RpsPendingView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        ResponseEntity.ok(pvpWebService.rpsPendingForOpponent(discordId, guildId))
    }

    @GetMapping("/{guildId}/rps/outgoing")
    @ResponseBody
    fun rpsOutgoingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.RpsPendingView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        ResponseEntity.ok(pvpWebService.rpsPendingForInitiator(discordId, guildId))
    }

    @GetMapping("/{guildId}/rps/active")
    @ResponseBody
    fun rpsActiveForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.RpsSessionView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        ResponseEntity.ok(pvpWebService.rpsActiveFor(discordId, guildId))
    }

    @GetMapping("/{guildId}/rps/{sessionId}")
    @ResponseBody
    fun rpsSession(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpWebService.RpsSessionView> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        val view = pvpWebService.rpsSessionView(sessionId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        ResponseEntity.ok(view)
    }

    @PostMapping("/{guildId}/rps/challenge")
    @ResponseBody
    fun rpsChallenge(
        @PathVariable guildId: Long,
        @RequestBody request: ChallengeRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                PvpActionResponse(
                    false,
                    error = if (status == 401) "Not signed in." else "You are not a member of that server."
                )
            )
        }
    ) { discordId ->
        val opponentDiscordId = request.opponentDiscordId.toLongOrNull()
            ?: return@requireMemberForJson ResponseEntity.badRequest()
                .body(PvpActionResponse(false, error = "Pick an opponent."))
        if (opponentDiscordId == discordId) {
            return@requireMemberForJson ResponseEntity.badRequest()
                .body(PvpActionResponse(false, error = "You can't challenge yourself."))
        }
        if (!economyWebService.isMember(opponentDiscordId, guildId)) {
            return@requireMemberForJson ResponseEntity.badRequest()
                .body(PvpActionResponse(false, error = "Pick someone from this server."))
        }
        pvpWebService.ensureOpponent(opponentDiscordId, guildId)
        val start = rpsService.startMatch(discordId, opponentDiscordId, guildId, request.stake)
        if (start !is PvpWagerService.StartOutcome.Ok) {
            return@requireMemberForJson ResponseEntity.badRequest()
                .body(PvpActionResponse(false, error = pvpStartErrorMessage(start, "rock-paper-scissors")))
        }
        val session = rpsSessionRegistry.register(
            guildId = guildId,
            initiatorDiscordId = discordId,
            opponentDiscordId = opponentDiscordId,
            stake = request.stake,
        )
        // Surface the offer to the opponent's PvP page in real-time —
        // their inbox view of the RPS tab repaints from this event
        // without polling. Initiator-side ack comes back on the POST
        // response, no separate SSE fan-out needed.
        pvpSseService.fanOutToUser(
            guildId, opponentDiscordId, "rps.offered",
            pvpWebService.rpsSessionView(session.id, opponentDiscordId) ?: emptyMap<String, Any>(),
        )
        ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = session.id, stake = request.stake))
    }

    @PostMapping("/{guildId}/rps/{sessionId}/accept")
    @ResponseBody
    fun rpsAccept(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = rpsSessionRegistry.get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this offer."))
        }
        if (session.opponentDiscordId != discordId) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "This isn't your offer."))
        }
        // Flip PENDING → LIVE atomically. If we lose the race (Discord-side accept, timeout) the registry
        // returns null and the user sees a 410.
        rpsSessionRegistry.accept(sessionId, onPickTimeout = ::resolveRpsOnPickTimeout)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        val debit = rpsService.acceptMatch(session.initiatorDiscordId, session.opponentDiscordId, guildId, session.stake)
        if (debit !is PvpWagerService.AcceptOutcome.Ok) {
            // Stakes weren't debitable post-accept (e.g. balance dropped between offer and accept).
            // Drain the session so neither side stays in LIVE with un-debited stakes.
            rpsSessionRegistry.forfeit(sessionId)
            pvpSseService.fanOutToBoth(
                guildId, session.initiatorDiscordId, session.opponentDiscordId, "rps.removed",
                mapOf("sessionId" to sessionId, "reason" to "balance_changed"),
            )
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = acceptErrorMessage(debit)))
        }
        // Both sides see the LIVE state — initiator's pick UI activates, opponent's
        // already-open accept ack flips into pick UI.
        pvpSseService.fanOutToBoth(
            guildId, session.initiatorDiscordId, session.opponentDiscordId, "rps.accepted",
            mapOf("sessionId" to sessionId, "state" to "LIVE", "stake" to session.stake),
        )
        return ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, stake = session.stake))
    }

    @PostMapping("/{guildId}/rps/{sessionId}/decline")
    @ResponseBody
    fun rpsDecline(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = rpsCloseOffer(
        guildId, sessionId, user, isInitiator = false, friendlyName = "decline",
    )

    @PostMapping("/{guildId}/rps/{sessionId}/cancel")
    @ResponseBody
    fun rpsCancel(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = rpsCloseOffer(
        guildId, sessionId, user, isInitiator = true, friendlyName = "cancel",
    )

    @PostMapping("/{guildId}/rps/{sessionId}/pick")
    @ResponseBody
    fun rpsPick(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @RequestBody request: RpsPickRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = rpsSessionRegistry.get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this match."))
        }
        if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "You aren't in this match."))
        }
        val choice = pvpWebService.parseRpsChoice(request.choice)
            ?: return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Pick rock, paper, or scissors."))
        val after = rpsSessionRegistry.recordPick(sessionId, discordId, choice)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match isn't live."))
        if (!after.bothPicked) {
            // Tell the other side that you've submitted (no choice revealed).
            val otherDiscordId =
                if (discordId == after.initiatorDiscordId) after.opponentDiscordId else after.initiatorDiscordId
            pvpSseService.fanOutToUser(
                guildId, otherDiscordId, "rps.picked",
                mapOf("sessionId" to sessionId, "opponentPicked" to true),
            )
            return ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, waitingForOpponent = true))
        }
        val consumed = rpsSessionRegistry.consumeForResolution(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        val outcome = rpsService.resolveMatch(
            initiatorDiscordId = consumed.initiatorDiscordId,
            opponentDiscordId = consumed.opponentDiscordId,
            guildId = guildId,
            stake = consumed.stake,
            initiatorChoice = consumed.picks[consumed.initiatorDiscordId],
            opponentChoice = consumed.picks[consumed.opponentDiscordId],
        )
        val resolution = translateRpsOutcome(outcome, consumed.initiatorDiscordId)
        // Both sides see the result. The picker who triggered resolution gets it in
        // their POST response; the other learns of it via this SSE event.
        pvpSseService.fanOutToBoth(
            guildId, consumed.initiatorDiscordId, consumed.opponentDiscordId, "rps.resolved",
            mapOf("sessionId" to sessionId, "outcome" to (resolution as Any? ?: emptyMap<String, Any>())),
        )
        return ResponseEntity.ok(
            PvpActionResponse(ok = true, sessionId = sessionId, outcome = resolution),
        )
    }

    @PostMapping("/{guildId}/rps/{sessionId}/forfeit")
    @ResponseBody
    fun rpsForfeit(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = rpsSessionRegistry.get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this match."))
        }
        if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "You aren't in this match."))
        }
        if (session.state != database.pvp.PvpSessionRegistry.Session.State.LIVE) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Match isn't live."))
        }
        val consumed = rpsSessionRegistry.forfeit(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        // Forfeiter's pick is dropped; opponent's pick (if any) survives. resolveMatch handles the
        // "exactly one picked" branch and pays the picker.
        val survivingPicks = consumed.picks.filterKeys { it != discordId }
        val outcome = rpsService.resolveMatch(
            initiatorDiscordId = consumed.initiatorDiscordId,
            opponentDiscordId = consumed.opponentDiscordId,
            guildId = guildId,
            stake = consumed.stake,
            initiatorChoice = survivingPicks[consumed.initiatorDiscordId],
            opponentChoice = survivingPicks[consumed.opponentDiscordId],
        )
        val resolution = translateRpsOutcome(outcome, consumed.initiatorDiscordId)
        pvpSseService.fanOutToBoth(
            guildId, consumed.initiatorDiscordId, consumed.opponentDiscordId, "rps.resolved",
            mapOf("sessionId" to sessionId, "outcome" to (resolution as Any? ?: emptyMap<String, Any>())),
        )
        return ResponseEntity.ok(
            PvpActionResponse(ok = true, sessionId = sessionId, outcome = resolution),
        )
    }

    /** Shared body for `/decline` (opponent) and `/cancel` (initiator) on a PENDING RPS offer. */
    private fun rpsCloseOffer(
        guildId: Long,
        sessionId: Long,
        user: OAuth2User,
        isInitiator: Boolean,
        friendlyName: String,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = rpsSessionRegistry.get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this offer."))
        }
        val expectedActor = if (isInitiator) session.initiatorDiscordId else session.opponentDiscordId
        if (discordId != expectedActor) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "This isn't your offer to $friendlyName."))
        }
        rpsSessionRegistry.decline(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        // Notify the OTHER side that the offer is gone — their inbox or outbox
        // entry should disappear in real-time.
        val otherDiscordId = if (isInitiator) session.opponentDiscordId else session.initiatorDiscordId
        pvpSseService.fanOutToUser(
            guildId, otherDiscordId, "rps.removed",
            mapOf("sessionId" to sessionId, "reason" to friendlyName),
        )
        return ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId))
    }

    /**
     * Pick-phase timeout callback. The registry hands us the drained
     * session; we resolve it with whatever picks were submitted so the
     * debited stakes don't get stranded, then SSE-broadcast the result
     * to both participants so their open pages reflect the timeout.
     * The Discord embed (if any) doesn't get updated by this path —
     * that's a known web-accept gap documented in the PR.
     */
    private fun resolveRpsOnPickTimeout(session: RpsSessionRegistry.Session) {
        val outcome = rpsService.resolveMatch(
            initiatorDiscordId = session.initiatorDiscordId,
            opponentDiscordId = session.opponentDiscordId,
            guildId = session.guildId,
            stake = session.stake,
            initiatorChoice = session.picks[session.initiatorDiscordId],
            opponentChoice = session.picks[session.opponentDiscordId],
        )
        val resolution = translateRpsOutcome(outcome, session.initiatorDiscordId)
        pvpSseService.fanOutToBoth(
            session.guildId, session.initiatorDiscordId, session.opponentDiscordId, "rps.resolved",
            mapOf(
                "sessionId" to session.id,
                "reason" to "pick_timeout",
                "outcome" to (resolution as Any? ?: emptyMap<String, Any>()),
            ),
        )
    }

    private fun translateRpsOutcome(
        outcome: RpsService.ResolveOutcome,
        initiatorDiscordId: Long,
    ): PvpWebService.PvpResolutionOutcome? = when (outcome) {
        is RpsService.ResolveOutcome.Win -> PvpWebService.PvpResolutionOutcome.rpsWin(outcome, initiatorDiscordId)
        is RpsService.ResolveOutcome.Draw -> PvpWebService.PvpResolutionOutcome.rpsDraw(outcome)
        is RpsService.ResolveOutcome.DoubleRefund -> PvpWebService.PvpResolutionOutcome.rpsDoubleRefund(outcome)
        RpsService.ResolveOutcome.Unknown -> null
    }

    private fun translateBoardOutcome(
        outcome: TurnBasedBoardWagerService.ResolveOutcome,
    ): PvpWebService.PvpResolutionOutcome? = when (outcome) {
        is TurnBasedBoardWagerService.ResolveOutcome.Win -> PvpWebService.PvpResolutionOutcome.boardWin(outcome)
        is TurnBasedBoardWagerService.ResolveOutcome.Draw -> PvpWebService.PvpResolutionOutcome.boardDraw(outcome)
        TurnBasedBoardWagerService.ResolveOutcome.Unknown -> null
    }

    // ─── TicTacToe ────────────────────────────────────────────────────

    @GetMapping("/{guildId}/tictactoe/pending")
    @ResponseBody
    fun tttPendingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.TicTacToePendingView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId -> ResponseEntity.ok(pvpWebService.ticTacToePendingForOpponent(discordId, guildId)) }

    @GetMapping("/{guildId}/tictactoe/outgoing")
    @ResponseBody
    fun tttOutgoingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.TicTacToePendingView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId -> ResponseEntity.ok(pvpWebService.ticTacToePendingForInitiator(discordId, guildId)) }

    @GetMapping("/{guildId}/tictactoe/active")
    @ResponseBody
    fun tttActiveForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.TicTacToeSessionView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId -> ResponseEntity.ok(pvpWebService.ticTacToeActiveFor(discordId, guildId)) }

    @GetMapping("/{guildId}/tictactoe/{sessionId}")
    @ResponseBody
    fun tttSession(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpWebService.TicTacToeSessionView> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        val view = pvpWebService.ticTacToeSessionView(sessionId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        ResponseEntity.ok(view)
    }

    @PostMapping("/{guildId}/tictactoe/challenge")
    @ResponseBody
    fun tttChallenge(
        @PathVariable guildId: Long,
        @RequestBody request: ChallengeRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardChallenge(
        guildId, request, user, gameLabel = "tic-tac-toe",
        start = { initId -> ticTacToeService.startMatch(initId, request.opponentDiscordId.toLong(), guildId, request.stake) },
        register = { initId, oppId, stake -> ticTacToeSessionRegistry.register(guildId, initId, oppId, stake) },
        sseEventName = "tictactoe.offered",
    )

    @PostMapping("/{guildId}/tictactoe/{sessionId}/accept")
    @ResponseBody
    fun tttAccept(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardAccept(
        guildId, sessionId, user,
        get = { ticTacToeSessionRegistry.get(it) },
        accept = { ticTacToeSessionRegistry.accept(it) { s -> resolveTttOnMoveTimeout(s) } },
        debit = { initId, oppId, stake -> ticTacToeService.acceptMatch(initId, oppId, guildId, stake) },
        forfeitOnDebitFail = { ticTacToeSessionRegistry.forfeit(it) },
        sseAcceptedEvent = "tictactoe.accepted",
        sseRemovedEvent = "tictactoe.removed",
    )

    @PostMapping("/{guildId}/tictactoe/{sessionId}/decline")
    @ResponseBody
    fun tttDecline(
        @PathVariable guildId: Long, @PathVariable sessionId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardCloseOffer(
        guildId, sessionId, user, isInitiator = false, friendlyName = "decline",
        get = { ticTacToeSessionRegistry.get(it) },
        decline = { ticTacToeSessionRegistry.decline(it) },
        sseEventName = "tictactoe.removed",
    )

    @PostMapping("/{guildId}/tictactoe/{sessionId}/cancel")
    @ResponseBody
    fun tttCancel(
        @PathVariable guildId: Long, @PathVariable sessionId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardCloseOffer(
        guildId, sessionId, user, isInitiator = true, friendlyName = "cancel",
        get = { ticTacToeSessionRegistry.get(it) },
        decline = { ticTacToeSessionRegistry.decline(it) },
        sseEventName = "tictactoe.removed",
    )

    @PostMapping("/{guildId}/tictactoe/{sessionId}/move")
    @ResponseBody
    fun tttMove(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @RequestBody request: BoardMoveRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = ticTacToeSessionRegistry.get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this match."))
        }
        if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "You aren't in this match."))
        }
        val cell = request.move
            ?: return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Missing cell."))
        if (cell < 0 || cell > 8) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Cell must be 0–8."))
        }
        val result = ticTacToeSessionRegistry.applyMove(sessionId, discordId, cell) { s -> resolveTttOnMoveTimeout(s) }
            ?: return ResponseEntity.status(409).body(PvpActionResponse(false, error = "Not your turn."))
        return handleTttResult(guildId, sessionId, session, result)
    }

    @PostMapping("/{guildId}/tictactoe/{sessionId}/forfeit")
    @ResponseBody
    fun tttForfeit(
        @PathVariable guildId: Long, @PathVariable sessionId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardForfeit(
        guildId, sessionId, user,
        get = { ticTacToeSessionRegistry.get(it) },
        forfeit = { ticTacToeSessionRegistry.forfeit(it) },
        resolveWithWinner = { init, opp, stake, winner ->
            ticTacToeService.resolveMatch(init, opp, guildId, stake, winner)
        },
        sseEventName = "tictactoe.resolved",
    )

    private fun handleTttResult(
        guildId: Long,
        sessionId: Long,
        session: TicTacToeSessionRegistry.Session,
        result: TicTacToeEngine.MoveResult,
    ): ResponseEntity<PvpActionResponse> {
        return when (result) {
            is TicTacToeEngine.MoveResult.Continued -> {
                // Tell the other side it's their turn — they refetch the
                // session state from the embedded sessionView payload.
                val viewer = session.initiatorDiscordId // viewer-agnostic; the recipient resolves myMark/myTurn from cells + currentActor
                val view = pvpWebService.ticTacToeSessionView(sessionId, viewer)
                pvpSseService.fanOutToBoth(
                    guildId, session.initiatorDiscordId, session.opponentDiscordId, "tictactoe.moved",
                    mapOf("sessionId" to sessionId, "view" to (view ?: emptyMap<String, Any>())),
                )
                ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId))
            }
            is TicTacToeEngine.MoveResult.Win -> {
                val consumed = ticTacToeSessionRegistry.consumeForResolution(sessionId) ?: session
                val winnerDiscordId = consumed.currentActorDiscordIdForWinner(result.winner)
                val outcome = ticTacToeService.resolveMatch(
                    consumed.initiatorDiscordId, consumed.opponentDiscordId, guildId, consumed.stake, winnerDiscordId,
                )
                val resolution = translateBoardOutcome(outcome)
                pvpSseService.fanOutToBoth(
                    guildId, consumed.initiatorDiscordId, consumed.opponentDiscordId, "tictactoe.resolved",
                    mapOf("sessionId" to sessionId, "outcome" to (resolution as Any? ?: emptyMap<String, Any>())),
                )
                ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, outcome = resolution))
            }
            is TicTacToeEngine.MoveResult.Draw -> {
                val consumed = ticTacToeSessionRegistry.consumeForResolution(sessionId) ?: session
                val outcome = ticTacToeService.resolveMatch(
                    consumed.initiatorDiscordId, consumed.opponentDiscordId, guildId, consumed.stake, null,
                )
                val resolution = translateBoardOutcome(outcome)
                pvpSseService.fanOutToBoth(
                    guildId, consumed.initiatorDiscordId, consumed.opponentDiscordId, "tictactoe.resolved",
                    mapOf("sessionId" to sessionId, "outcome" to (resolution as Any? ?: emptyMap<String, Any>())),
                )
                ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, outcome = resolution))
            }
            TicTacToeEngine.MoveResult.IllegalCell ->
                ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Cell must be 0–8."))
            TicTacToeEngine.MoveResult.Occupied ->
                ResponseEntity.badRequest().body(PvpActionResponse(false, error = "That cell is already taken."))
        }
    }

    private fun TicTacToeSessionRegistry.Session.currentActorDiscordIdForWinner(winner: TicTacToeEngine.Mark): Long =
        when (winner) {
            TicTacToeEngine.Mark.X -> initiatorDiscordId
            TicTacToeEngine.Mark.O -> opponentDiscordId
        }

    private fun resolveTttOnMoveTimeout(session: TicTacToeSessionRegistry.Session) {
        // The on-clock player just lost by timeout. The opponent takes the pot.
        val opponentDiscordId = if (session.currentActorDiscordId() == session.initiatorDiscordId)
            session.opponentDiscordId else session.initiatorDiscordId
        val outcome = ticTacToeService.resolveMatch(
            session.initiatorDiscordId, session.opponentDiscordId, session.guildId, session.stake, opponentDiscordId,
        )
        val resolution = translateBoardOutcome(outcome)
        pvpSseService.fanOutToBoth(
            session.guildId, session.initiatorDiscordId, session.opponentDiscordId, "tictactoe.resolved",
            mapOf(
                "sessionId" to session.id, "reason" to "move_timeout",
                "outcome" to (resolution as Any? ?: emptyMap<String, Any>()),
            ),
        )
    }

    // ─── Connect 4 ────────────────────────────────────────────────────

    @GetMapping("/{guildId}/connect4/pending")
    @ResponseBody
    fun c4PendingForMe(
        @PathVariable guildId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.Connect4PendingView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId -> ResponseEntity.ok(pvpWebService.connect4PendingForOpponent(discordId, guildId)) }

    @GetMapping("/{guildId}/connect4/outgoing")
    @ResponseBody
    fun c4OutgoingForMe(
        @PathVariable guildId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.Connect4PendingView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId -> ResponseEntity.ok(pvpWebService.connect4PendingForInitiator(discordId, guildId)) }

    @GetMapping("/{guildId}/connect4/active")
    @ResponseBody
    fun c4ActiveForMe(
        @PathVariable guildId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<PvpWebService.Connect4SessionView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId -> ResponseEntity.ok(pvpWebService.connect4ActiveFor(discordId, guildId)) }

    @GetMapping("/{guildId}/connect4/{sessionId}")
    @ResponseBody
    fun c4Session(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpWebService.Connect4SessionView> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        val view = pvpWebService.connect4SessionView(sessionId, discordId)
            ?: return@requireMemberForJsonNoBody ResponseEntity.status(404).build()
        ResponseEntity.ok(view)
    }

    @PostMapping("/{guildId}/connect4/challenge")
    @ResponseBody
    fun c4Challenge(
        @PathVariable guildId: Long,
        @RequestBody request: ChallengeRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardChallenge(
        guildId, request, user, gameLabel = "connect 4",
        start = { initId -> connect4Service.startMatch(initId, request.opponentDiscordId.toLong(), guildId, request.stake) },
        register = { initId, oppId, stake -> connect4SessionRegistry.register(guildId, initId, oppId, stake) },
        sseEventName = "connect4.offered",
    )

    @PostMapping("/{guildId}/connect4/{sessionId}/accept")
    @ResponseBody
    fun c4Accept(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardAccept(
        guildId, sessionId, user,
        get = { connect4SessionRegistry.get(it) },
        accept = { connect4SessionRegistry.accept(it) { s -> resolveC4OnMoveTimeout(s) } },
        debit = { initId, oppId, stake -> connect4Service.acceptMatch(initId, oppId, guildId, stake) },
        forfeitOnDebitFail = { connect4SessionRegistry.forfeit(it) },
        sseAcceptedEvent = "connect4.accepted",
        sseRemovedEvent = "connect4.removed",
    )

    @PostMapping("/{guildId}/connect4/{sessionId}/decline")
    @ResponseBody
    fun c4Decline(
        @PathVariable guildId: Long, @PathVariable sessionId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardCloseOffer(
        guildId, sessionId, user, isInitiator = false, friendlyName = "decline",
        get = { connect4SessionRegistry.get(it) },
        decline = { connect4SessionRegistry.decline(it) },
        sseEventName = "connect4.removed",
    )

    @PostMapping("/{guildId}/connect4/{sessionId}/cancel")
    @ResponseBody
    fun c4Cancel(
        @PathVariable guildId: Long, @PathVariable sessionId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardCloseOffer(
        guildId, sessionId, user, isInitiator = true, friendlyName = "cancel",
        get = { connect4SessionRegistry.get(it) },
        decline = { connect4SessionRegistry.decline(it) },
        sseEventName = "connect4.removed",
    )

    @PostMapping("/{guildId}/connect4/{sessionId}/move")
    @ResponseBody
    fun c4Move(
        @PathVariable guildId: Long,
        @PathVariable sessionId: Long,
        @RequestBody request: BoardMoveRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = connect4SessionRegistry.get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this match."))
        }
        if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "You aren't in this match."))
        }
        val column = request.move
            ?: return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Missing column."))
        if (column < 0 || column > 6) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Column must be 0–6."))
        }
        val result = connect4SessionRegistry.applyMove(sessionId, discordId, column) { s -> resolveC4OnMoveTimeout(s) }
            ?: return ResponseEntity.status(409).body(PvpActionResponse(false, error = "Not your turn."))
        return handleC4Result(guildId, sessionId, session, result)
    }

    @PostMapping("/{guildId}/connect4/{sessionId}/forfeit")
    @ResponseBody
    fun c4Forfeit(
        @PathVariable guildId: Long, @PathVariable sessionId: Long, @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<PvpActionResponse> = boardForfeit(
        guildId, sessionId, user,
        get = { connect4SessionRegistry.get(it) },
        forfeit = { connect4SessionRegistry.forfeit(it) },
        resolveWithWinner = { init, opp, stake, winner ->
            connect4Service.resolveMatch(init, opp, guildId, stake, winner)
        },
        sseEventName = "connect4.resolved",
    )

    private fun handleC4Result(
        guildId: Long,
        sessionId: Long,
        session: Connect4SessionRegistry.Session,
        result: Connect4Engine.MoveResult,
    ): ResponseEntity<PvpActionResponse> {
        return when (result) {
            is Connect4Engine.MoveResult.Continued -> {
                val view = pvpWebService.connect4SessionView(sessionId, session.initiatorDiscordId)
                pvpSseService.fanOutToBoth(
                    guildId, session.initiatorDiscordId, session.opponentDiscordId, "connect4.moved",
                    mapOf("sessionId" to sessionId, "view" to (view ?: emptyMap<String, Any>())),
                )
                ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId))
            }
            is Connect4Engine.MoveResult.Win -> {
                val consumed = connect4SessionRegistry.consumeForResolution(sessionId) ?: session
                val winnerDiscordId = consumed.currentActorDiscordIdForWinner(result.winner)
                val outcome = connect4Service.resolveMatch(
                    consumed.initiatorDiscordId, consumed.opponentDiscordId, guildId, consumed.stake, winnerDiscordId,
                )
                val resolution = translateBoardOutcome(outcome)
                pvpSseService.fanOutToBoth(
                    guildId, consumed.initiatorDiscordId, consumed.opponentDiscordId, "connect4.resolved",
                    mapOf("sessionId" to sessionId, "outcome" to (resolution as Any? ?: emptyMap<String, Any>())),
                )
                ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, outcome = resolution))
            }
            is Connect4Engine.MoveResult.Draw -> {
                val consumed = connect4SessionRegistry.consumeForResolution(sessionId) ?: session
                val outcome = connect4Service.resolveMatch(
                    consumed.initiatorDiscordId, consumed.opponentDiscordId, guildId, consumed.stake, null,
                )
                val resolution = translateBoardOutcome(outcome)
                pvpSseService.fanOutToBoth(
                    guildId, consumed.initiatorDiscordId, consumed.opponentDiscordId, "connect4.resolved",
                    mapOf("sessionId" to sessionId, "outcome" to (resolution as Any? ?: emptyMap<String, Any>())),
                )
                ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, outcome = resolution))
            }
            Connect4Engine.MoveResult.InvalidColumn ->
                ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Column must be 0–6."))
            Connect4Engine.MoveResult.ColumnFull ->
                ResponseEntity.badRequest().body(PvpActionResponse(false, error = "That column is full."))
        }
    }

    private fun Connect4SessionRegistry.Session.currentActorDiscordIdForWinner(winner: Connect4Engine.Mark): Long =
        when (winner) {
            Connect4Engine.Mark.RED -> initiatorDiscordId
            Connect4Engine.Mark.YELLOW -> opponentDiscordId
        }

    private fun resolveC4OnMoveTimeout(session: Connect4SessionRegistry.Session) {
        val opponentDiscordId = if (session.currentActorDiscordId() == session.initiatorDiscordId)
            session.opponentDiscordId else session.initiatorDiscordId
        val outcome = connect4Service.resolveMatch(
            session.initiatorDiscordId, session.opponentDiscordId, session.guildId, session.stake, opponentDiscordId,
        )
        val resolution = translateBoardOutcome(outcome)
        pvpSseService.fanOutToBoth(
            session.guildId, session.initiatorDiscordId, session.opponentDiscordId, "connect4.resolved",
            mapOf(
                "sessionId" to session.id, "reason" to "move_timeout",
                "outcome" to (resolution as Any? ?: emptyMap<String, Any>()),
            ),
        )
    }

    // ─── Shared board-game helpers ────────────────────────────────────
    //
    // TTT and C4 share the same Challenge / Accept / Decline / Cancel /
    // Forfeit shapes — only the registry and service types differ. The
    // helpers below collapse the 5x duplication those endpoints would
    // otherwise carry, parameterised on per-game lambdas. The /move
    // endpoint stays per-game because the parameter shape (cell vs
    // column) and the engine MoveResult sealed types diverge.

    private inline fun <S : database.boardgame.TurnBasedBoardSessionRegistry.Session> boardChallenge(
        guildId: Long,
        request: ChallengeRequest,
        user: OAuth2User,
        gameLabel: String,
        start: (Long) -> PvpWagerService.StartOutcome,
        register: (Long, Long, Long) -> S,
        sseEventName: String,
    ): ResponseEntity<PvpActionResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                PvpActionResponse(false, error = if (status == 401) "Not signed in." else "You are not a member of that server.")
            )
        }
    ) { discordId ->
        val opponentDiscordId = request.opponentDiscordId.toLongOrNull()
            ?: return@requireMemberForJson ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Pick an opponent."))
        if (opponentDiscordId == discordId) {
            return@requireMemberForJson ResponseEntity.badRequest().body(PvpActionResponse(false, error = "You can't challenge yourself."))
        }
        if (!economyWebService.isMember(opponentDiscordId, guildId)) {
            return@requireMemberForJson ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Pick someone from this server."))
        }
        pvpWebService.ensureOpponent(opponentDiscordId, guildId)
        val started = start(discordId)
        if (started !is PvpWagerService.StartOutcome.Ok) {
            return@requireMemberForJson ResponseEntity.badRequest().body(PvpActionResponse(false, error = pvpStartErrorMessage(started, gameLabel)))
        }
        val session = register(discordId, opponentDiscordId, request.stake)
        pvpSseService.fanOutToUser(
            guildId, opponentDiscordId, sseEventName,
            mapOf("sessionId" to session.id),
        )
        ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = session.id, stake = request.stake))
    }

    private inline fun <S : database.boardgame.TurnBasedBoardSessionRegistry.Session> boardAccept(
        guildId: Long,
        sessionId: Long,
        user: OAuth2User,
        get: (Long) -> S?,
        accept: (Long) -> S?,
        debit: (Long, Long, Long) -> PvpWagerService.AcceptOutcome,
        forfeitOnDebitFail: (Long) -> S?,
        sseAcceptedEvent: String,
        sseRemovedEvent: String,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this offer."))
        }
        if (session.opponentDiscordId != discordId) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "This isn't your offer."))
        }
        accept(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        val outcome = debit(session.initiatorDiscordId, session.opponentDiscordId, session.stake)
        if (outcome !is PvpWagerService.AcceptOutcome.Ok) {
            forfeitOnDebitFail(sessionId)
            pvpSseService.fanOutToBoth(
                guildId, session.initiatorDiscordId, session.opponentDiscordId, sseRemovedEvent,
                mapOf("sessionId" to sessionId, "reason" to "balance_changed"),
            )
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = acceptErrorMessage(outcome)))
        }
        pvpSseService.fanOutToBoth(
            guildId, session.initiatorDiscordId, session.opponentDiscordId, sseAcceptedEvent,
            mapOf("sessionId" to sessionId, "state" to "LIVE", "stake" to session.stake),
        )
        return ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, stake = session.stake))
    }

    private inline fun <S : database.boardgame.TurnBasedBoardSessionRegistry.Session> boardCloseOffer(
        guildId: Long,
        sessionId: Long,
        user: OAuth2User,
        isInitiator: Boolean,
        friendlyName: String,
        get: (Long) -> S?,
        decline: (Long) -> S?,
        sseEventName: String,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this offer."))
        }
        val expectedActor = if (isInitiator) session.initiatorDiscordId else session.opponentDiscordId
        if (discordId != expectedActor) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "This isn't your offer to $friendlyName."))
        }
        decline(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "This offer already resolved or expired."))
        val otherDiscordId = if (isInitiator) session.opponentDiscordId else session.initiatorDiscordId
        pvpSseService.fanOutToUser(
            guildId, otherDiscordId, sseEventName,
            mapOf("sessionId" to sessionId, "reason" to friendlyName),
        )
        return ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId))
    }

    private inline fun <S : database.boardgame.TurnBasedBoardSessionRegistry.Session> boardForfeit(
        guildId: Long,
        sessionId: Long,
        user: OAuth2User,
        get: (Long) -> S?,
        forfeit: (Long) -> S?,
        resolveWithWinner: (Long, Long, Long, Long?) -> TurnBasedBoardWagerService.ResolveOutcome,
        sseEventName: String,
    ): ResponseEntity<PvpActionResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PvpActionResponse(false, error = "Not signed in."))
        val session = get(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        if (session.guildId != guildId) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Wrong guild for this match."))
        }
        if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) {
            return ResponseEntity.status(403).body(PvpActionResponse(false, error = "You aren't in this match."))
        }
        if (session.state != database.pvp.PvpSessionRegistry.Session.State.LIVE) {
            return ResponseEntity.badRequest().body(PvpActionResponse(false, error = "Match isn't live."))
        }
        val consumed = forfeit(sessionId)
            ?: return ResponseEntity.status(410).body(PvpActionResponse(false, error = "Match already ended."))
        val winnerDiscordId = if (discordId == consumed.initiatorDiscordId)
            consumed.opponentDiscordId else consumed.initiatorDiscordId
        val outcome = resolveWithWinner(consumed.initiatorDiscordId, consumed.opponentDiscordId, consumed.stake, winnerDiscordId)
        val resolution = translateBoardOutcome(outcome)
        pvpSseService.fanOutToBoth(
            guildId, consumed.initiatorDiscordId, consumed.opponentDiscordId, sseEventName,
            mapOf("sessionId" to sessionId, "reason" to "forfeit", "outcome" to (resolution as Any? ?: emptyMap<String, Any>())),
        )
        return ResponseEntity.ok(PvpActionResponse(ok = true, sessionId = sessionId, outcome = resolution))
    }

    private fun pvpStartErrorMessage(outcome: PvpWagerService.StartOutcome, gameLabel: String): String = when (outcome) {
        is PvpWagerService.StartOutcome.InvalidStake -> "Stake must be between ${outcome.min} and ${outcome.max} credits."
        is PvpWagerService.StartOutcome.InvalidOpponent -> "You can't challenge yourself at $gameLabel."
        is PvpWagerService.StartOutcome.InitiatorInsufficient ->
            "You need ${outcome.needed} credits but only have ${outcome.have}."
        is PvpWagerService.StartOutcome.OpponentInsufficient ->
            "Opponent only has ${outcome.have} credits — they can't cover a ${outcome.needed} stake."
        PvpWagerService.StartOutcome.UnknownInitiator -> "No user record yet. Try another TobyBot command first."
        PvpWagerService.StartOutcome.UnknownOpponent -> "Opponent has no user record in this guild yet."
        is PvpWagerService.StartOutcome.Ok -> "OK"
    }

    private fun acceptErrorMessage(outcome: PvpWagerService.AcceptOutcome): String = when (outcome) {
        is PvpWagerService.AcceptOutcome.InitiatorInsufficient ->
            "Challenger no longer has enough credits."
        is PvpWagerService.AcceptOutcome.OpponentInsufficient ->
            "You no longer have enough credits."
        PvpWagerService.AcceptOutcome.UnknownInitiator -> "Challenger's user record vanished."
        PvpWagerService.AcceptOutcome.UnknownOpponent -> "Your user record vanished."
        is PvpWagerService.AcceptOutcome.Ok -> "OK"
    }
}

data class ChallengeRequest(val opponentDiscordId: String = "", val stake: Long = 0)

data class ChallengeResponse(
    val ok: Boolean,
    val error: String? = null,
    val duelId: Long? = null,
    val stake: Long? = null
)

/** Body returned from any RPS / TTT / C4 action endpoint. Reused
 *  across challenge / accept / decline / cancel / pick / forfeit so the
 *  client has one shape to parse regardless of which step the user just
 *  took. `outcome` is populated only on terminal resolutions. */
data class PvpActionResponse(
    val ok: Boolean,
    val error: String? = null,
    val sessionId: Long? = null,
    val stake: Long? = null,
    val waitingForOpponent: Boolean = false,
    val outcome: PvpWebService.PvpResolutionOutcome? = null,
)

/** RPS pick body. The choice string is parsed in
 *  [PvpWebService.parseRpsChoice] — case-insensitive, fails the
 *  request on an unknown value. */
data class RpsPickRequest(val choice: String = "")

/** TTT cell index (0–8, row-major) or C4 column index (0–6). One DTO
 *  for both because the validation is per-game on the controller side. */
data class BoardMoveRequest(val move: Int? = null)

data class DuelActionResponse(
    val ok: Boolean,
    val error: String? = null,
    // Stringified — JS renders [winnerDiscordId] into a `<@…>` mention, and a
    // numeric round-trip on a 18-digit Discord snowflake would produce the
    // wrong id (rounded past JS Number.MAX_SAFE_INTEGER).
    val winnerDiscordId: String? = null,
    val loserDiscordId: String? = null,
    val stake: Long? = null,
    val pot: Long? = null,
    val winnerNewBalance: Long? = null,
    val loserNewBalance: Long? = null,
    val lossTribute: Long? = null,
)
