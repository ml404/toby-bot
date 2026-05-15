package bot.toby.modal.modals

import bot.toby.command.commands.economy.TipEmbeds
import bot.toby.helpers.UserDtoHelper
import core.modal.Modal
import core.modal.ModalContext
import database.service.TipService
import database.service.TipService.TipOutcome
import org.springframework.stereotype.Component

/**
 * Submission handler for the optional-note modal opened by `/tip user
 * amount`. Encodes the recipient discord id and amount into the modal
 * `customId` (`tip_message:<recipientId>:<amount>`) so the modal can
 * execute the tip on submit without any cross-interaction state.
 *
 * Form-style UX makes the note prominent (vs. buried as an optional
 * slash option) so users are more likely to personalise. The note is
 * still optional — leaving the field blank submits a no-note tip.
 *
 * `DefaultModalManager` routes via the prefix before `:`, so this
 * modal's `name = "tip_message"` matches all `tip_message:...` ids.
 */
@Component
class TipMessageModal(
    private val tipService: TipService,
    private val userDtoHelper: UserDtoHelper,
) : Modal {
    override val name = MODAL_NAME

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guildId = ctx.guild.idLong
        val senderDiscordId = event.user.idLong

        val parts = event.modalId.split(':')
        val recipientId = parts.getOrNull(1)?.toLongOrNull()
        val amount = parts.getOrNull(2)?.toLongOrNull()
        if (recipientId == null || amount == null) {
            event.hook.sendMessageEmbeds(
                TipEmbeds.errorEmbed("Tip form lost its recipient/amount context — try `/tip` again.")
            ).setEphemeral(true).queue()
            return
        }

        val rawNote = event.getValue(FIELD_NOTE)?.asString?.trim()
        val note = rawNote?.takeIf { it.isNotEmpty() }

        // Lazy-create the recipient row — same as the slash command path.
        userDtoHelper.calculateUserDto(recipientId, guildId)

        val outcome = tipService.tip(
            senderDiscordId = senderDiscordId,
            recipientDiscordId = recipientId,
            guildId = guildId,
            amount = amount,
            note = note,
        )

        when (outcome) {
            is TipOutcome.Ok -> {
                // Public notification with recipient ping — channel send so the
                // ephemeral defer on the modal doesn't constrain visibility.
                event.channel.sendMessageEmbeds(TipEmbeds.okEmbed(outcome))
                    .addContent("<@${outcome.recipient}>")
                    .queue()
                event.hook.sendMessage("Tip sent.").setEphemeral(true).queue()
            }
            else -> event.hook.sendMessageEmbeds(TipEmbeds.errorEmbed(failureMessage(outcome)))
                .setEphemeral(true).queue()
        }
    }

    private fun failureMessage(outcome: TipOutcome): String = when (outcome) {
        is TipOutcome.InvalidAmount ->
            "Tip amount must be between ${outcome.min} and ${outcome.max} credits."
        is TipOutcome.InvalidRecipient -> when (outcome.reason) {
            TipOutcome.InvalidRecipient.Reason.SELF -> "You can't tip yourself."
            TipOutcome.InvalidRecipient.Reason.BOT -> "You can't tip a bot."
            TipOutcome.InvalidRecipient.Reason.MISSING -> "Recipient does not exist."
        }
        is TipOutcome.InsufficientCredits ->
            "You only have ${outcome.have} credits but tried to send ${outcome.needed}."
        is TipOutcome.DailyCapExceeded ->
            "Daily tip cap reached. You've sent ${outcome.sentToday}/${outcome.cap} today."
        TipOutcome.UnknownSender -> "No user record yet. Try another TobyBot command first."
        TipOutcome.UnknownRecipient -> "Recipient has no user record in this guild yet."
        is TipOutcome.Ok -> error("unreachable") // handled above
    }

    companion object {
        const val MODAL_NAME = "tip_message"
        const val FIELD_NOTE = "tip_note"

        fun customId(recipientDiscordId: Long, amount: Long): String =
            "$MODAL_NAME:$recipientDiscordId:$amount"
    }
}
