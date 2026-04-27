package web.controller

import database.economy.Dice
import database.service.DiceService
import database.service.DiceService.RollOutcome
import database.service.JackpotService
import database.service.TobyCoinMarketService
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
import web.util.WebGuildAccess
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
    private val jackpotService: JackpotService,
    private val marketService: TobyCoinMarketService,
    private val jda: JDA
) {

    @GetMapping
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireMemberForPage(
        user, guildId, economyWebService, ra, lobbyPath = "/casino/guilds"
    ) { discordId ->
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireMemberForPage "redirect:/casino/guilds"
        }

        val profile = userService.getUserById(discordId, guildId)
        val balance = profile?.socialCredit ?: 0L
        val tobyCoins = profile?.tobyCoins ?: 0L
        val marketPrice = marketService.getMarket(guildId)?.price ?: 0.0

        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", balance)
        model.addAttribute("tobyCoins", tobyCoins)
        model.addAttribute("marketPrice", marketPrice)
        model.addAttribute("minStake", Dice.MIN_STAKE)
        model.addAttribute("maxStake", Dice.MAX_STAKE)
        model.addAttribute("sides", Dice.DEFAULT_SIDES)
        model.addAttribute("multiplier", Dice.DEFAULT_MULTIPLIER)
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("username", user.displayName())
        "dice"
    }

    @PostMapping("/roll")
    @ResponseBody
    fun roll(
        @PathVariable guildId: Long,
        @RequestBody request: RollRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<RollResponse> = WebGuildAccess.requireMemberForJson(
        user, guildId, economyWebService,
        errorBuilder = { status ->
            ResponseEntity.status(status).body(
                RollResponse(
                    false,
                    if (status == 401) "Not signed in." else "You are not a member of that server."
                )
            )
        }
    ) { discordId ->
        when (val outcome = diceService.roll(discordId, guildId, request.stake, request.prediction, request.autoTopUp)) {
            is RollOutcome.Win -> ResponseEntity.ok(
                RollResponse(
                    ok = true,
                    landed = outcome.landed,
                    predicted = outcome.predicted,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
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
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice,
                    lossTribute = outcome.lossTribute.takeIf { it > 0L }
                )
            )

            is RollOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                RollResponse(false, "Need ${outcome.stake} credits, you have ${outcome.have}.")
            )

            is RollOutcome.InsufficientCoinsForTopUp -> ResponseEntity.badRequest().body(
                RollResponse(false, "Need ${outcome.needed} TOBY to cover the shortfall, you have ${outcome.have}.")
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

data class RollRequest(val prediction: Int = 0, val stake: Long = 0, val autoTopUp: Boolean = false)

data class RollResponse(
    val ok: Boolean,
    val error: String? = null,
    val landed: Int? = null,
    val predicted: Int? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null,
    val lossTribute: Long? = null
)
