package web.controller

import database.economy.ScratchCard
import database.economy.SlotMachine
import database.service.JackpotService
import database.service.ScratchService
import database.service.ScratchService.ScratchOutcome
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
import web.util.discordIdOrNull
import web.util.displayName

@Controller
@RequestMapping("/casino/{guildId}/scratch")
class ScratchController(
    private val scratchService: ScratchService,
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

        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", balance)
        model.addAttribute("tobyCoins", tobyCoins)
        model.addAttribute("marketPrice", marketPrice)
        model.addAttribute("minStake", ScratchCard.MIN_STAKE)
        model.addAttribute("maxStake", ScratchCard.MAX_STAKE)
        model.addAttribute("cellCount", ScratchCard.CELL_COUNT)
        model.addAttribute("matchThreshold", ScratchCard.MATCH_THRESHOLD)
        model.addAttribute("matchCounts", (ScratchCard.MATCH_THRESHOLD..ScratchCard.CELL_COUNT).toList())
        model.addAttribute("payoutTable", scratchPayoutRows())
        model.addAttribute("jackpotPool", jackpotService.getPool(guildId))
        model.addAttribute("username", user.displayName())
        return "scratch"
    }

    // Per-symbol payout row: one cell per match count from MATCH_THRESHOLD
    // to CELL_COUNT. Driven by ScratchCard.multiplierFor() so the table
    // stays locked to the live formula — change the bases or floor in code
    // and the page updates without anyone editing HTML.
    private fun scratchPayoutRows(): List<ScratchPayoutRow> =
        SlotMachine.Symbol.entries.map { symbol ->
            ScratchPayoutRow(
                symbol = symbol.display,
                multipliers = (ScratchCard.MATCH_THRESHOLD..ScratchCard.CELL_COUNT).map { k ->
                    "${ScratchCard.multiplierFor(symbol, k)}×"
                }
            )
        }

    @PostMapping("/scratch")
    @ResponseBody
    fun scratch(
        @PathVariable guildId: Long,
        @RequestBody request: ScratchRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ScratchResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ScratchResponse(false, "Not signed in."))
        if (!economyWebService.isMember(discordId, guildId)) {
            return ResponseEntity.status(403).body(ScratchResponse(false, "You are not a member of that server."))
        }

        return when (val outcome = scratchService.scratch(discordId, guildId, request.stake, request.autoTopUp)) {
            is ScratchOutcome.Win -> ResponseEntity.ok(
                ScratchResponse(
                    ok = true,
                    cells = outcome.cells.map { it.display },
                    winningSymbol = outcome.winningSymbol.display,
                    matchCount = outcome.matchCount,
                    net = outcome.net,
                    payout = outcome.payout,
                    newBalance = outcome.newBalance,
                    win = true,
                    jackpotPayout = outcome.jackpotPayout.takeIf { it > 0L },
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )

            is ScratchOutcome.Lose -> ResponseEntity.ok(
                ScratchResponse(
                    ok = true,
                    cells = outcome.cells.map { it.display },
                    net = -outcome.stake,
                    payout = 0L,
                    newBalance = outcome.newBalance,
                    win = false,
                    soldTobyCoins = outcome.soldTobyCoins.takeIf { it > 0L },
                    newPrice = outcome.newPrice
                )
            )

            is ScratchOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                ScratchResponse(false, "Need ${outcome.stake} credits, you have ${outcome.have}.")
            )

            is ScratchOutcome.InsufficientCoinsForTopUp -> ResponseEntity.badRequest().body(
                ScratchResponse(false, "Need ${outcome.needed} TOBY to cover the shortfall, you have ${outcome.have}.")
            )

            is ScratchOutcome.InvalidStake -> ResponseEntity.badRequest().body(
                ScratchResponse(false, "Stake must be between ${outcome.min} and ${outcome.max} credits.")
            )

            ScratchOutcome.UnknownUser -> ResponseEntity.badRequest().body(
                ScratchResponse(false, "No user record yet. Try another TobyBot command first.")
            )
        }
    }
}

data class ScratchPayoutRow(val symbol: String, val multipliers: List<String>)

data class ScratchRequest(val stake: Long = 0, val autoTopUp: Boolean = false)

data class ScratchResponse(
    val ok: Boolean,
    val error: String? = null,
    val cells: List<String>? = null,
    val winningSymbol: String? = null,
    val matchCount: Int? = null,
    val net: Long? = null,
    val payout: Long? = null,
    val newBalance: Long? = null,
    val win: Boolean? = null,
    val jackpotPayout: Long? = null,
    val soldTobyCoins: Long? = null,
    val newPrice: Double? = null
)
