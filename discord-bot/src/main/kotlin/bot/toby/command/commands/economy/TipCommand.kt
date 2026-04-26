package bot.toby.command.commands.economy

import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.service.TipService
import database.service.TipService.TipOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * `/tip user:<member> amount:<int> message:<text?>` — peer-to-peer
 * social-credit transfer. Goes through the same [TipService] the web
 * UI uses, so the per-sender daily outgoing cap and the audit log
 * stay consistent across surfaces.
 */
@Component
class TipCommand @Autowired constructor(
    private val tipService: TipService,
    private val userDtoHelper: UserDtoHelper,
) : EconomyCommand {

    override val name: String = "tip"
    override val description: String =
        "Send another user some social credit. ${TipService.MIN_TIP}-${TipService.MAX_TIP} per tip."

    companion object {
        private const val OPT_USER = "user"
        private const val OPT_AMOUNT = "amount"
        private const val OPT_MESSAGE = "message"
        private val OK_COLOR = Color(87, 242, 135)
        private val ERROR_COLOR = Color(237, 66, 69)
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, OPT_USER, "Recipient", true),
        OptionData(
            OptionType.INTEGER,
            OPT_AMOUNT,
            "Credits to send (${TipService.MIN_TIP}–${TipService.MAX_TIP})",
            true
        )
            .setMinValue(TipService.MIN_TIP)
            .setMaxValue(TipService.MAX_TIP),
        OptionData(OptionType.STRING, OPT_MESSAGE, "Optional note", false)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }
        val targetUser = event.getOption(OPT_USER)?.asUser ?: run {
            replyError(event, "You must specify a recipient.", deleteDelay); return
        }
        if (targetUser.isBot) {
            replyError(event, "You can't tip a bot.", deleteDelay); return
        }
        if (targetUser.idLong == requestingUserDto.discordId) {
            replyError(event, "You can't tip yourself.", deleteDelay); return
        }
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            replyError(event, "You must specify an amount.", deleteDelay); return
        }
        val note = event.getOption(OPT_MESSAGE)?.asString

        // Lazy-create the recipient row so we can credit them even if they've
        // never run a TobyBot command before.
        userDtoHelper.calculateUserDto(targetUser.idLong, guild.idLong)

        val outcome = tipService.tip(
            senderDiscordId = requestingUserDto.discordId,
            recipientDiscordId = targetUser.idLong,
            guildId = guild.idLong,
            amount = amount,
            note = note
        )
        replyOutcome(event, outcome, deleteDelay)
    }

    private fun replyOutcome(
        event: SlashCommandInteractionEvent,
        outcome: TipOutcome,
        deleteDelay: Int
    ) {
        if (outcome is TipOutcome.Ok) {
            val embed = EmbedBuilder()
                .setTitle("💸 Tip sent")
                .setDescription(
                    "<@${outcome.sender}> tipped <@${outcome.recipient}> **${outcome.amount} credits**." +
                        outcome.note?.let { "\n*${it}*" }.orEmpty()
                )
                .addField("Your balance", "${outcome.senderNewBalance} credits", true)
                .addField(
                    "Tipped today",
                    "${outcome.sentTodayAfter}/${outcome.dailyCap}",
                    true
                )
                .setColor(OK_COLOR)
                .build()
            // addContent on the message (not the embed description) so the
            // <@recipient> mention actually pings — embed-mention pings are silent.
            event.hook.sendMessageEmbeds(embed)
                .addContent("<@${outcome.recipient}>")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val embed = when (outcome) {
            is TipOutcome.InvalidAmount -> errorEmbed(
                "Tip amount must be between ${outcome.min} and ${outcome.max} credits."
            )

            is TipOutcome.InvalidRecipient -> errorEmbed(
                when (outcome.reason) {
                    TipOutcome.InvalidRecipient.Reason.SELF -> "You can't tip yourself."
                    TipOutcome.InvalidRecipient.Reason.BOT -> "You can't tip a bot."
                    TipOutcome.InvalidRecipient.Reason.MISSING -> "Recipient does not exist."
                }
            )

            is TipOutcome.InsufficientCredits -> errorEmbed(
                "You only have ${outcome.have} credits but tried to send ${outcome.needed}."
            )

            is TipOutcome.DailyCapExceeded -> errorEmbed(
                "Daily tip cap reached. You've sent ${outcome.sentToday}/${outcome.cap} today; " +
                    "${outcome.cap - outcome.sentToday} credits of headroom remain."
            )

            TipOutcome.UnknownSender -> errorEmbed(
                "No user record yet. Try another TobyBot command first."
            )

            TipOutcome.UnknownRecipient -> errorEmbed(
                "Recipient has no user record in this guild yet."
            )

            is TipOutcome.Ok -> error("unreachable") // handled above
        }
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun errorEmbed(message: String) = EmbedBuilder()
        .setTitle("💸 Tip")
        .setDescription(message)
        .setColor(ERROR_COLOR)
        .build()

    private fun replyError(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.sendMessageEmbeds(errorEmbed(message)).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
