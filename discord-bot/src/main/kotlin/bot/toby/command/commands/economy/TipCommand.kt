package bot.toby.command.commands.economy

import bot.toby.modal.modals.TipMessageModal
import core.command.CommandContext
import database.dto.UserDto
import database.service.TipService
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.modals.Modal
import org.springframework.stereotype.Component

/**
 * `/tip user:<member> amount:<int>` — peer-to-peer social-credit
 * transfer. After validating the recipient + amount, opens
 * [TipMessageModal] which collects the optional note and executes the
 * tip via [TipService.tip] (same service path the web UI uses, so
 * daily cap and audit-log semantics stay consistent across surfaces).
 *
 * The note used to be a third slash option — easy to miss and rarely
 * filled in. Moving it into a dedicated form field encourages
 * personalised tips and clears clutter from the slash UX.
 */
@Component
class TipCommand : EconomyCommand {

    override val name: String = "tip"
    override val description: String =
        "Send another user some social credit. ${TipService.MIN_TIP}-${TipService.MAX_TIP} per tip."

    companion object {
        private const val OPT_USER = "user"
        private const val OPT_AMOUNT = "amount"
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
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        if (event.guild == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue()
            return
        }
        val targetUser = event.getOption(OPT_USER)?.asUser ?: run {
            event.reply("You must specify a recipient.").setEphemeral(true).queue()
            return
        }
        if (targetUser.isBot) {
            event.reply("You can't tip a bot.").setEphemeral(true).queue()
            return
        }
        if (targetUser.idLong == requestingUserDto.discordId) {
            event.reply("You can't tip yourself.").setEphemeral(true).queue()
            return
        }
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            event.reply("You must specify an amount.").setEphemeral(true).queue()
            return
        }

        event.replyModal(buildTipMessageModal(targetUser.idLong, amount)).queue()
    }

    private fun buildTipMessageModal(recipientDiscordId: Long, amount: Long): Modal {
        val noteInput = TextInput.create(TipMessageModal.FIELD_NOTE, TextInputStyle.PARAGRAPH)
            .setPlaceholder("Optional note that ships with the tip.")
            .setRequired(false)
            .setRequiredRange(0, TipService.MAX_NOTE_LENGTH)
            .build()
        return Modal.create(
            TipMessageModal.customId(recipientDiscordId, amount),
            "Tip $amount credits",
        )
            .addComponents(Label.of("Note (optional, max ${TipService.MAX_NOTE_LENGTH} chars)", noteInput))
            .build()
    }
}
