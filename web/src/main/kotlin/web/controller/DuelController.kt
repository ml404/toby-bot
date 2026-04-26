package web.controller

import database.duel.PendingDuelRegistry
import database.service.DuelService
import database.service.DuelService.AcceptOutcome
import database.service.DuelService.StartOutcome
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.context.ApplicationEventPublisher
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
import web.event.WebDuelOfferedEvent
import web.service.DuelWebService
import web.service.EconomyWebService
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Web surface for /duel. Same [DuelService] the Discord command uses,
 * and the same in-memory [PendingDuelRegistry] — a duel offered in
 * Discord can be accepted via the web inbox and vice versa.
 */
@Controller
@RequestMapping("/duel")
class DuelController(
    private val duelService: DuelService,
    private val duelWebService: DuelWebService,
    private val pendingDuelRegistry: PendingDuelRegistry,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
    private val jda: JDA,
    private val eventPublisher: ApplicationEventPublisher,
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
        return "duel-guilds"
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/duel/guilds"
    ) { discordId ->
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/duel/guilds"
        }

        val profile = userService.getUserById(discordId, guildId)
        val balance = profile?.socialCredit ?: 0L
        val pending = duelWebService.pendingForOpponent(discordId, guildId)
        val outgoing = duelWebService.pendingForInitiator(discordId, guildId)
        val members = economyWebService.getGuildMembers(guildId).filter { it.id != discordId.toString() }

        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", balance)
        model.addAttribute("minStake", DuelService.MIN_STAKE)
        model.addAttribute("maxStake", DuelService.MAX_STAKE)
        model.addAttribute("pending", pending)
        model.addAttribute("outgoing", outgoing)
        model.addAttribute("ttlLabel", PendingDuelRegistry.formatTtl(pendingDuelRegistry.ttl))
        model.addAttribute("members", members)
        model.addAttribute("username", user.displayName())
        "duel"
    }

    @GetMapping("/{guildId}/pending")
    @ResponseBody
    fun pendingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<DuelWebService.PendingDuelView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        ResponseEntity.ok(duelWebService.pendingForOpponent(discordId, guildId))
    }

    @GetMapping("/{guildId}/outgoing")
    @ResponseBody
    fun outgoingForMe(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<List<DuelWebService.PendingDuelView>> = WebGuildAccess.requireMemberForJsonNoBody(
        user, guildId, economyWebService
    ) { discordId ->
        ResponseEntity.ok(duelWebService.pendingForInitiator(discordId, guildId))
    }

    @PostMapping("/{guildId}/challenge")
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
        if (request.opponentDiscordId == discordId) {
            return@requireMemberForJson ResponseEntity.badRequest().body(
                ChallengeResponse(false, error = "You can't duel yourself.")
            )
        }
        if (!economyWebService.isMember(request.opponentDiscordId, guildId)) {
            return@requireMemberForJson ResponseEntity.badRequest().body(
                ChallengeResponse(false, error = "Pick someone from this server.")
            )
        }

        duelWebService.ensureOpponent(request.opponentDiscordId, guildId)

        val start = duelService.startDuel(
            initiatorDiscordId = discordId,
            opponentDiscordId = request.opponentDiscordId,
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
            opponentDiscordId = request.opponentDiscordId,
            stake = request.stake
        )
        eventPublisher.publishEvent(
            WebDuelOfferedEvent(
                guildId = guildId,
                duelId = offer.id,
                initiatorDiscordId = discordId,
                opponentDiscordId = request.opponentDiscordId,
                stake = request.stake
            )
        )
        ResponseEntity.ok(
            ChallengeResponse(ok = true, duelId = offer.id, stake = request.stake)
        )
    }

    @PostMapping("/{guildId}/{duelId}/accept")
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
                    winnerDiscordId = outcome.winnerDiscordId,
                    loserDiscordId = outcome.loserDiscordId,
                    stake = outcome.stake,
                    pot = outcome.pot,
                    winnerNewBalance = outcome.winnerNewBalance,
                    loserNewBalance = outcome.loserNewBalance,
                    lossTribute = outcome.lossTribute
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

    @PostMapping("/{guildId}/{duelId}/decline")
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

    @PostMapping("/{guildId}/{duelId}/cancel")
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

data class ChallengeRequest(val opponentDiscordId: Long = 0, val stake: Long = 0)

data class ChallengeResponse(
    val ok: Boolean,
    val error: String? = null,
    val duelId: Long? = null,
    val stake: Long? = null
)

data class DuelActionResponse(
    val ok: Boolean,
    val error: String? = null,
    val winnerDiscordId: Long? = null,
    val loserDiscordId: Long? = null,
    val stake: Long? = null,
    val pot: Long? = null,
    val winnerNewBalance: Long? = null,
    val loserNewBalance: Long? = null,
    val lossTribute: Long? = null
)
