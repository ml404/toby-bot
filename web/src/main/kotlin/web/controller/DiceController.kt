package web.controller

import database.economy.Dice
import database.service.DiceService
import database.service.DiceService.RollOutcome
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
 * Web surface for the `/dice` minigame. GET renders the picker UI;
 * POST runs the roll via [DiceService.roll] and returns JSON.
 *
 * Both surfaces share [DiceService] so Discord and web can't drift on
 * payout maths or balance debit/credit semantics.
 */
@Controller
@RequestMapping("/casino/{guildId}/dice")
class DiceController(
    private val diceService: DiceService,
    private val economyWebService: EconomyWebService,
    private val userService: UserService,
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
        model.addAttribute("minStake", Dice.MIN_STAKE)
        model.addAttribute("maxStake", Dice.MAX_STAKE)
        model.addAttribute("sides", Dice.DEFAULT_SIDES)
        model.addAttribute("multiplier", Dice.DEFAULT_MULTIPLIER)
        model.addAttribute("username", user.displayName())
        return "dice"
    }

    @PostMapping("/roll")
    @ResponseBody
    fun roll(
        @PathVariable guildId: Long,
        @RequestBody request: RollRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<RollResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(RollResponse(false, "Not signed in."))
        if (!economyWebService.isMember(discordId, guildId)) {
            return ResponseEntity.status(403).body(RollResponse(false, "You are not a member of that server."))
        }

        return when (val outcome = diceService.roll(discordId, guildId, request.stake, request.prediction)) {
            is RollOutcome.Win -> ResponseEntity.ok(
                RollResponse(
                    ok = true,
                    landed = outcome.landed,
                    predicted = outcome.predicted,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true
                )
            )

            is RollOutcome.Lose -> ResponseEntity.ok(
                RollResponse(
                    ok = true,
                    landed = outcome.landed,
                    predicted = outcome.predicted,
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false
                )
            )

            is RollOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                RollResponse(false, "Need ${outcome.stake} credits, you have ${outcome.have}.")
            )

            is RollOutcome.InvalidStake -> ResponseEntity.badRequest().body(
                RollResponse(false, "Stake must be between ${outcome.min} and ${outcome.max} credits.")
            )

            is RollOutcome.InvalidPrediction -> ResponseEntity.badRequest().body(
                RollResponse(false, "Pick a number between ${outcome.min} and ${outcome.max}.")
            )

            RollOutcome.UnknownUser -> ResponseEntity.badRequest().body(
                RollResponse(false, "No user record yet. Try another TobyBot command first.")
            )
        }
    }
}

data class RollRequest(val prediction: Int = 0, val stake: Long = 0)

data class RollResponse(
    val ok: Boolean,
    val error: String? = null,
    val landed: Int? = null,
    val predicted: Int? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null
)
