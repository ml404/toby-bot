package web.controller

import database.economy.Coinflip
import database.service.CoinflipService
import database.service.CoinflipService.FlipOutcome
import database.service.JackpotService
import database.service.UserService
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

/**
 * Web surface for the `/coinflip` minigame. GET renders the picker UI
 * (heads vs tails buttons + stake input); POST runs the flip via
 * [CoinflipService.flip] and returns JSON for the JS animation to settle
 * on.
 *
 * Both surfaces share [CoinflipService] so Discord and web can't drift
 * on payout maths or balance debit/credit semantics.
 */
@Controller
@RequestMapping("/casino/{guildId}/coinflip")
class CoinflipController(
    private val coinflipService: CoinflipService,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val jda: JDA
) {

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
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

        val balance = userService.getUserById(discordId, guildId)?.socialCredit ?: 0L

        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", balance)
        model.addAttribute("minStake", Coinflip.MIN_STAKE)
        model.addAttribute("maxStake", Coinflip.MAX_STAKE)
        model.addAttribute("multiplier", Coinflip.DEFAULT_MULTIPLIER)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("username", user.displayName())
        return "coinflip"
    }

    @PostMapping("/flip")
    @ResponseBody
    fun flip(
        @PathVariable guildId: Long,
        @RequestBody request: FlipRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<FlipResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(FlipResponse(false, "Not signed in."))
        if (!economyWebService.isMember(discordId, guildId)) {
            return ResponseEntity.status(403).body(FlipResponse(false, "You are not a member of that server."))
        }
        val side = parseSide(request.side)
            ?: return ResponseEntity.badRequest().body(FlipResponse(false, "Pick a side: HEADS or TAILS."))

        return when (val outcome = coinflipService.flip(discordId, guildId, request.stake, side)) {
            is FlipOutcome.Win -> ResponseEntity.ok(
                FlipResponse(
                    ok = true,
                    landed = outcome.landed.name,
                    predicted = outcome.predicted.name,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L }
                )
            )

            is FlipOutcome.Lose -> ResponseEntity.ok(
                FlipResponse(
                    ok = true,
                    landed = outcome.landed.name,
                    predicted = outcome.predicted.name,
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false
                )
            )

            is FlipOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                FlipResponse(false, "Need ${outcome.stake} credits, you have ${outcome.have}.")
            )

            is FlipOutcome.InvalidStake -> ResponseEntity.badRequest().body(
                FlipResponse(false, "Stake must be between ${outcome.min} and ${outcome.max} credits.")
            )

            FlipOutcome.UnknownUser -> ResponseEntity.badRequest().body(
                FlipResponse(false, "No user record yet. Try another TobyBot command first.")
            )
        }
    }

    private fun parseSide(raw: String?): Coinflip.Side? = when (raw?.uppercase()) {
        "HEADS" -> Coinflip.Side.HEADS
        "TAILS" -> Coinflip.Side.TAILS
        else -> null
    }
}

data class FlipRequest(val side: String = "", val stake: Long = 0)

data class FlipResponse(
    val ok: Boolean,
    val error: String? = null,
    val landed: String? = null,
    val predicted: String? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null
)
