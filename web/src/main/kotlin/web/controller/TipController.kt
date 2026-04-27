package web.controller

import database.service.TipService
import database.service.TipService.TipOutcome
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
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.event.WebTipSentEvent
import web.service.EconomyWebService
import web.service.TipWebService
import web.util.discordIdOrNull
import web.util.displayName
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Web surface for /tip. Mirrors the Discord [bot.toby.command.commands.economy.TipCommand]
 * — same [TipService.tip] call, same daily-cap accounting, same audit log.
 */
@Controller
@RequestMapping("/tip")
class TipController(
    private val tipService: TipService,
    private val tipWebService: TipWebService,
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
        return "tip-guilds"
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/tip/guilds"

        if (!economyWebService.isMember(discordId, guildId)) {
            ra.addFlashAttribute("error", "You are not a member of that server.")
            return "redirect:/tip/guilds"
        }
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/tip/guilds"
        }

        val profile = userService.getUserById(discordId, guildId)
        val balance = profile?.socialCredit ?: 0L
        val today = LocalDate.now(ZoneOffset.UTC)
        val sentToday = tipWebService.getDailyTipped(discordId, guildId, today)
        val members = economyWebService.getGuildMembers(guildId).filter { it.id != discordId.toString() }

        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("guildName", guild.name)
        model.addAttribute("balance", balance)
        model.addAttribute("minTip", TipService.MIN_TIP)
        model.addAttribute("maxTip", TipService.MAX_TIP)
        model.addAttribute("dailyCap", TipService.DEFAULT_TIP_DAILY_CAP)
        model.addAttribute("sentToday", sentToday)
        model.addAttribute("dailyHeadroom", (TipService.DEFAULT_TIP_DAILY_CAP - sentToday).coerceAtLeast(0L))
        model.addAttribute("members", members)
        model.addAttribute("username", user.displayName())
        return "tip"
    }

    @PostMapping("/{guildId}")
    @ResponseBody
    fun tip(
        @PathVariable guildId: Long,
        @RequestBody request: TipRequest,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<TipResponse> {
        val discordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(TipResponse(false, error = "Not signed in."))
        if (!economyWebService.isMember(discordId, guildId)) {
            return ResponseEntity.status(403).body(TipResponse(false, error = "You are not a member of that server."))
        }

        val recipientDiscordId = request.recipientDiscordId.toLong()
        if (recipientDiscordId == discordId) {
            return ResponseEntity.badRequest().body(TipResponse(false, error = "You can't tip yourself."))
        }
        if (!economyWebService.isMember(recipientDiscordId, guildId)) {
            return ResponseEntity.badRequest().body(TipResponse(false, error = "Pick someone from this server."))
        }

        // Ensure recipient row exists (lazy-create through the helper-style path).
        tipWebService.ensureRecipient(recipientDiscordId, guildId)

        val outcome = tipService.tip(
            senderDiscordId = discordId,
            recipientDiscordId = recipientDiscordId,
            guildId = guildId,
            amount = request.amount,
            note = request.note?.takeIf { it.isNotBlank() }
        )
        return when (outcome) {
            is TipOutcome.Ok -> {
                eventPublisher.publishEvent(
                    WebTipSentEvent(
                        guildId = guildId,
                        senderDiscordId = outcome.sender,
                        recipientDiscordId = outcome.recipient,
                        amount = outcome.amount,
                        note = outcome.note,
                        senderNewBalance = outcome.senderNewBalance,
                        recipientNewBalance = outcome.recipientNewBalance,
                        sentTodayAfter = outcome.sentTodayAfter,
                        dailyCap = outcome.dailyCap
                    )
                )
                ResponseEntity.ok(
                    TipResponse(
                        ok = true,
                        senderNewBalance = outcome.senderNewBalance,
                        recipientNewBalance = outcome.recipientNewBalance,
                        sentTodayAfter = outcome.sentTodayAfter,
                        dailyCap = outcome.dailyCap,
                        amount = outcome.amount,
                        note = outcome.note
                    )
                )
            }
            is TipOutcome.InvalidAmount -> ResponseEntity.badRequest().body(
                TipResponse(false, error = "Tip must be between ${outcome.min} and ${outcome.max} credits.")
            )
            is TipOutcome.InvalidRecipient -> ResponseEntity.badRequest().body(
                TipResponse(false, error = when (outcome.reason) {
                    TipOutcome.InvalidRecipient.Reason.SELF -> "You can't tip yourself."
                    TipOutcome.InvalidRecipient.Reason.BOT -> "You can't tip a bot."
                    TipOutcome.InvalidRecipient.Reason.MISSING -> "Recipient does not exist."
                })
            )
            is TipOutcome.InsufficientCredits -> ResponseEntity.badRequest().body(
                TipResponse(false, error = "You have ${outcome.have} credits but tried to send ${outcome.needed}.")
            )
            is TipOutcome.DailyCapExceeded -> ResponseEntity.badRequest().body(
                TipResponse(
                    false,
                    error = "Daily tip cap reached. Sent ${outcome.sentToday}/${outcome.cap} today."
                )
            )
            TipOutcome.UnknownSender -> ResponseEntity.badRequest().body(
                TipResponse(false, error = "No user record yet. Try another TobyBot command first.")
            )
            TipOutcome.UnknownRecipient -> ResponseEntity.badRequest().body(
                TipResponse(false, error = "Recipient has no user record in this guild yet.")
            )
        }
    }
}

data class TipRequest(
    val recipientDiscordId: String = "",
    val amount: Long = 0,
    val note: String? = null
)

data class TipResponse(
    val ok: Boolean,
    val error: String? = null,
    val amount: Long? = null,
    val note: String? = null,
    val senderNewBalance: Long? = null,
    val recipientNewBalance: Long? = null,
    val sentTodayAfter: Long? = null,
    val dailyCap: Long? = null
)
