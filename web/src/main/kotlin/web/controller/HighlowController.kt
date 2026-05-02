package web.controller

import database.economy.Highlow
import database.service.HighlowService
import database.service.HighlowService.PlayOutcome
import jakarta.servlet.http.HttpSession
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
import web.service.EconomyWebService
import web.util.WebGuildAccess

@Controller
@RequestMapping("/casino/{guildId}/highlow")
class HighlowController(
    private val highlowService: HighlowService,
    private val economyWebService: EconomyWebService,
    private val pageContext: CasinoPageContext,
) {

    private val startErrors = CasinoOutcomeMapper { msg -> StartResponse(false, msg) }
    private val playErrors = CasinoOutcomeMapper { msg -> PlayResponse(false, msg) }

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        session: HttpSession,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/casino/guilds"
    ) { discordId ->
        pageContext.populate(model, guildId, discordId, user) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/casino/guilds"
        }

        // Stake commits first, anchor is dealt after the player locks it.
        // If a round is already locked in this session, surface it so the
        // player isn't asked to lock again on refresh.
        val activeAnchor = activeAnchor(session, guildId)
        val activeStake = activeStake(session, guildId)

        model.addAttribute("minStake", Highlow.MIN_STAKE)
        model.addAttribute("maxStake", Highlow.MAX_STAKE)
        model.addAttribute("anchor", activeAnchor)
        model.addAttribute("anchorLabel", activeAnchor?.let { cardLabel(it) } ?: "?")
        model.addAttribute("higherMultiplier", activeAnchor?.let {
            highlowService.payoutMultiplier(it, Highlow.Direction.HIGHER)
        })
        model.addAttribute("lowerMultiplier", activeAnchor?.let {
            highlowService.payoutMultiplier(it, Highlow.Direction.LOWER)
        })
        model.addAttribute("activeStake", activeStake)
        "highlow"
    }

    @PostMapping("/start")
    @ResponseBody
    fun start(
        @PathVariable guildId: Long,
        @RequestBody request: StartRequest,
        @AuthenticationPrincipal user: OAuth2User,
        session: HttpSession
    ): ResponseEntity<StartResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = startErrors.errorBuilder
    ) { _ ->
        if (request.stake < Highlow.MIN_STAKE || request.stake > Highlow.MAX_STAKE) {
            return@requireMemberForJson startErrors.invalidStake(Highlow.MIN_STAKE, Highlow.MAX_STAKE)
        }

        val anchor = highlowService.dealAnchor()
        storeStake(session, guildId, request.stake)
        storeAutoTopUp(session, guildId, request.autoTopUp)
        storeAnchor(session, guildId, anchor)

        ResponseEntity.ok(
            StartResponse(
                ok = true,
                anchor = anchor,
                anchorLabel = cardLabel(anchor),
                higherMultiplier = highlowService.payoutMultiplier(anchor, Highlow.Direction.HIGHER),
                lowerMultiplier = highlowService.payoutMultiplier(anchor, Highlow.Direction.LOWER)
            )
        )
    }

    @PostMapping("/play")
    @ResponseBody
    fun play(
        @PathVariable guildId: Long,
        @RequestBody request: PlayRequest,
        @AuthenticationPrincipal user: OAuth2User,
        session: HttpSession
    ): ResponseEntity<PlayResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService, errorBuilder = playErrors.errorBuilder
    ) { discordId ->
        val direction = parseDirection(request.direction)
            ?: return@requireMemberForJson playErrors.badRequest("Pick a direction: HIGHER or LOWER.")

        val anchor = activeAnchor(session, guildId)
        val stake = activeStake(session, guildId)
        if (anchor == null || stake == null) {
            return@requireMemberForJson playErrors.badRequest("No active round — lock a stake first.")
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

        when (outcome) {
            is PlayOutcome.Win -> ResponseEntity.ok(
                PlayResponse(
                    ok = true,
                    anchor = outcome.anchor,
                    next = outcome.next,
                    direction = outcome.direction.name,
                    net = outcome.net,
                    payout = outcome.payout,
                    multiplier = outcome.multiplier,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
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
                    lossTribute = outcome.lossTribute.takeIf { it > 0L },
                )
            )

            is PlayOutcome.InsufficientCredits -> playErrors.insufficientCredits(outcome.stake, outcome.have)
            is PlayOutcome.InsufficientCoinsForTopUp -> playErrors.insufficientCoinsForTopUp(outcome.needed, outcome.have)
            is PlayOutcome.InvalidStake -> playErrors.invalidStake(outcome.min, outcome.max)
            PlayOutcome.UnknownUser -> playErrors.unknownUser()
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
    override val ok: Boolean,
    override val error: String? = null,
    val anchor: Int? = null,
    val anchorLabel: String? = null,
    val higherMultiplier: Double? = null,
    val lowerMultiplier: Double? = null
) : CasinoResponseLike

data class PlayRequest(val direction: String = "")

data class PlayResponse(
    override val ok: Boolean,
    override val error: String? = null,
    val anchor: Int? = null,
    val next: Int? = null,
    val direction: String? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val multiplier: Double? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null,
) : CasinoResponseLike
