package web.controller

import database.duel.PendingDuelRegistry
import database.service.DuelService
import database.service.DuelService.AcceptOutcome
import database.service.DuelService.StartOutcome
import database.service.UserService
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
import web.event.WebDuelOfferedEvent
import web.service.EconomyWebService
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
 * vice versa. Rock-paper-scissors, tic-tac-toe, and connect 4 tabs
 * render in the unified page but are placeholders until their
 * controller endpoints land in a follow-up PR.
 *
 * Old `/duel/...` URLs are kept alive via [DuelRedirectController]
 * which 301/308s every former route into the new `/pvp/...` space.
 */
@Controller
@RequestMapping("/pvp")
class PvpController(
    private val duelService: DuelService,
    private val pvpWebService: PvpWebService,
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
        request: HttpServletRequest,
        model: Model,
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
        ) { "/pvp/$it" }?.let { return it }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "pvp-guilds"
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
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
}

data class ChallengeRequest(val opponentDiscordId: String = "", val stake: Long = 0)

data class ChallengeResponse(
    val ok: Boolean,
    val error: String? = null,
    val duelId: Long? = null,
    val stake: Long? = null
)

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
