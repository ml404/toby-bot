package web.controller

import database.economy.Highlow
import database.service.HighlowService
import database.service.HighlowService.PlayOutcome
import database.service.JackpotService
import database.service.TobyCoinMarketService
import database.service.UserService
import jakarta.servlet.http.HttpSession
import net.dv8tion.jda.api.JDA
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
import web.service.EconomyWebService
import web.util.discordIdOrNull
import web.util.displayName

@Controller
@RequestMapping("/casino/{guildId}/highlow")
class HighlowController(
    private val highlowService: HighlowService,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val marketService: TobyCoinMarketService,
    private val jda: JDA
) {

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        session: HttpSession,
        model: Model,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/casino/guilds"
        if (!economyWebService.isMember(discordId, guildId)) {
            ra.addFlashAttribute("error", "You are not a member of that server.")
            return "redirect:/casino/guilds"
        }
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/casino/guilds"
        }

        val profile = userService.getUserById(discordId, guildId)
        val balance = profile?.socialCredit ?: 0L
        val tobyCoins = profile?.tobyCoins ?: 0L
        val marketPrice = marketService.getMarket(guildId)?.price ?: 0.0

        // Stake commits first, anchor is dealt after the player locks it.
        // If a round is already locked in this session, surface it so the
        // player isn't asked to lock again on refresh.
        val activeAnchor = activeAnchor(session, guildId)
        val activeStake = activeStake(session, guildId)

        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", balance)
        model.addAttribute("tobyCoins", tobyCoins)
        model.addAttribute("marketPrice", marketPrice)
        model.addAttribute("minStake", Highlow.MIN_STAKE)
        model.addAttribute("maxStake", Highlow.MAX_STAKE)
        model.addAttribute("multiplier", Highlow.DEFAULT_MULTIPLIER)
        model.addAttribute("anchor", activeAnchor)
        model.addAttribute("anchorLabel", activeAnchor?.let { cardLabel(it) } ?: "?")
        model.addAttribute("activeStake", activeStake)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("username", user.displayName())
        return "highlow"
    }

    @PostMapping("/start")
    @ResponseBody
    fun start(
        @PathVariable guildId: Long,
        @RequestBody request: StartRequest,
        @AuthenticationPrincipal user: OAuth2User,
        session: HttpSession
    ): ResponseEntity<StartResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(StartResponse(false, "Not signed in."))
        if (!economyWebService.isMember(discordId, guildId)) {
            return ResponseEntity.status(403).body(StartResponse(false, "You are not a member of that server."))
        }
        if (request.stake < Highlow.MIN_STAKE || request.stake > Highlow.MAX_STAKE) {
            return ResponseEntity.badRequest().body(
                StartResponse(false, "Stake must be between ${Highlow.MIN_STAKE} and ${Highlow.MAX_STAKE} credits.")
            )
        }

        val anchor = highlowService.dealAnchor()
        storeStake(session, guildId, request.stake)
        storeAutoTopUp(session, guildId, request.autoTopUp)
        storeAnchor(session, guildId, anchor)

        return ResponseEntity.ok(StartResponse(ok = true, anchor = anchor, anchorLabel = cardLabel(anchor)))
    }

    @PostMapping("/play")
    @ResponseBody
    fun play(
        @PathVariable guildId: Long,
        @RequestBody request: PlayRequest,
        @AuthenticationPrincipal user: OAuth2User,
        session: HttpSession
    ): ResponseEntity<PlayResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(PlayResponse(false, "Not signed in."))
        if (!economyWebService.isMember(discordId, guildId)) {
            return ResponseEntity.status(403).body(PlayResponse(false, "You are not a member of that server."))
        }
        val direction = parseDirection(request.direction)
            ?: return ResponseEntity.badRequest().body(PlayResponse(false, "Pick a direction: HIGHER or LOWER."))

        val anchor = activeAnchor(session, guildId)
        val stake = activeStake(session, guildId)
        if (anchor == null || stake == null) {
            return ResponseEntity.badRequest().body(
                PlayResponse(false, "No active round — lock a stake first.")
            )
        }
        val autoTopUp = activeAutoTopUp(session, guildId)

        val outcome = highlowService.play(discordId, guildId, stake, direction, anchor, autoTopUp)

        // Once a card is drawn the round is over either way; clear the
        // session so the next round starts with a fresh stake commit.
        // Validation/credit failures *do not* draw the next card, so we
        // keep the locked stake+anchor in place for the player to retry.
        val consumed = outcome is PlayOutcome.Win || outcome is PlayOutcome.Lose
        if (consumed) {
            clearRound(session, guildId)
        }

        return when (outcome) {
            is PlayOutcome.Win -> ResponseEntity.ok(
                PlayResponse(
                    ok = true,
                    anchor = outcome.anchor,
                    next = outcome.next,
                    direction = outcome.direction.name,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )

            is PlayOutcome.Lose -> ResponseEntity.ok(
                PlayResponse(
                    ok = true,
                    anchor = outcome.anchor,
                    next = outcome.next,
                    direction = outcome.direction.name,
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.takeIf { it > 0L }
                )
            )

            is PlayOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                PlayResponse(false, "Need ${outcome.stake} credits, you have ${outcome.have}.")
            )

            is PlayOutcome.InsufficientCoinsForTopUp -> ResponseEntity.badRequest().body(
                PlayResponse(false, "Need ${outcome.needed} TOBY to cover the shortfall, you have ${outcome.have}.")
            )

            is PlayOutcome.InvalidStake -> ResponseEntity.badRequest().body(
                PlayResponse(false, "Stake must be between ${outcome.min} and ${outcome.max} credits.")
            )

            PlayOutcome.UnknownUser -> ResponseEntity.badRequest().body(
                PlayResponse(false, "No user record yet. Try another TobyBot command first.")
            )
        }
    }

    private fun parseDirection(raw: String?): Highlow.Direction? = when (raw?.uppercase()) {
        "HIGHER" -> Highlow.Direction.HIGHER
        "LOWER" -> Highlow.Direction.LOWER
        else -> null
    }

    private fun anchorKey(guildId: Long): String = "highlow.anchor.$guildId"
    private fun stakeKey(guildId: Long): String = "highlow.stake.$guildId"
    private fun autoTopUpKey(guildId: Long): String = "highlow.autoTopUp.$guildId"

    private fun activeAnchor(session: HttpSession, guildId: Long): Int? =
        session.getAttribute(anchorKey(guildId)) as? Int

    private fun activeStake(session: HttpSession, guildId: Long): Long? =
        session.getAttribute(stakeKey(guildId)) as? Long

    private fun activeAutoTopUp(session: HttpSession, guildId: Long): Boolean =
        (session.getAttribute(autoTopUpKey(guildId)) as? Boolean) == true

    private fun storeAnchor(session: HttpSession, guildId: Long, anchor: Int) {
        session.setAttribute(anchorKey(guildId), anchor)
    }

    private fun storeStake(session: HttpSession, guildId: Long, stake: Long) {
        session.setAttribute(stakeKey(guildId), stake)
    }

    private fun storeAutoTopUp(session: HttpSession, guildId: Long, autoTopUp: Boolean) {
        session.setAttribute(autoTopUpKey(guildId), autoTopUp)
    }

    private fun clearRound(session: HttpSession, guildId: Long) {
        session.removeAttribute(anchorKey(guildId))
        session.removeAttribute(stakeKey(guildId))
        session.removeAttribute(autoTopUpKey(guildId))
    }

    private fun cardLabel(n: Int): String = when (n) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> n.toString()
    }
}

data class StartRequest(val stake: Long = 0, val autoTopUp: Boolean = false)

data class StartResponse(
    val ok: Boolean,
    val error: String? = null,
    val anchor: Int? = null,
    val anchorLabel: String? = null
)

data class PlayRequest(val direction: String = "")

data class PlayResponse(
    val ok: Boolean,
    val error: String? = null,
    val anchor: Int? = null,
    val next: Int? = null,
    val direction: String? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null
)
