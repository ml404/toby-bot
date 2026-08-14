package bot.toby.modal.modals

import bot.toby.command.commands.economy.TipEmbeds
import bot.toby.helpers.UserDtoHelper
import core.modal.Modal
import core.modal.ModalContext
import database.service.social.TipService
import database.service.social.TipService.TipOutcome
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.modals.Modal as JdaModal

/**
 * Submission handler for the tip form, and the builder for both shapes of it.
 *
 * The `customId` carries everything the submission needs, so no cross-
 * interaction state has to survive in memory:
 * `tip_message:<recipientId>:<amount>[:<channelId>:<messageId>]`.
 *
 *  - `/tip user amount` already took the amount as an option, so it fills that
 *    segment and the form asks only for the note.
 *  - The **Tip this** right-click entry and the profile-card button have no
 *    amount yet, so they leave the segment blank and the form asks for both.
 *    The trailing pair, when present, is the message being tipped *for* — the
 *    announcement links back to it, which is the whole reason to tip from a
 *    message rather than by name.
 *
 * Form-style UX makes the note prominent (vs. buried as an optional slash
 * option) so users are more likely to personalise. The note is still optional —
 * leaving the field blank submits a no-note tip.
 *
 * `DefaultModalManager` routes via the prefix before `:`, so this modal's
 * `name = "tip_message"` matches all `tip_message:...` ids.
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

        // Typed amount wins over the id: only one of the two is ever present,
        // and treating a filled field as authoritative keeps the precedence
        // obvious rather than depending on which form opened.
        val typed = event.getValue(FIELD_AMOUNT)?.asString?.trim()?.takeIf { it.isNotEmpty() }
        if (typed != null && typed.toLongOrNull() == null) {
            event.hook.sendMessageEmbeds(
                TipEmbeds.errorEmbed("\"$typed\" isn't a number — enter a whole number of credits.")
            ).setEphemeral(true).queue()
            return
        }
        val amount = typed?.toLong() ?: parts.getOrNull(2)?.toLongOrNull()

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
                event.channel.sendMessageEmbeds(TipEmbeds.okEmbed(outcome, jumpLink(guildId, parts)))
                    .addContent("<@${outcome.recipient}>")
                    .queue()
                event.hook.sendMessage("Tip sent.").setEphemeral(true).queue()
            }
            else -> event.hook.sendMessageEmbeds(TipEmbeds.errorEmbed(failureMessage(outcome)))
                .setEphemeral(true).queue()
        }
    }

    /**
     * The message this tip was for, when the form was opened from one.
     *
     * @return a Discord jump URL, or null for the by-name paths where there is
     *   no message to point at.
     */
    private fun jumpLink(guildId: Long, parts: List<String>): String? {
        val channelId = parts.getOrNull(3)?.toLongOrNull() ?: return null
        val messageId = parts.getOrNull(4)?.toLongOrNull() ?: return null
        return "https://discord.com/channels/$guildId/$channelId/$messageId"
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
        const val FIELD_AMOUNT = "tip_amount"

        /** Discord's cap on a modal title. */
        private const val TITLE_LIMIT = 45

        fun customId(recipientDiscordId: Long, amount: Long): String =
            "$MODAL_NAME:$recipientDiscordId:$amount"

        /**
         * The amount segment is left empty for the paths that ask for it in the
         * form, which keeps the positions of everything after it fixed.
         */
        fun askAmountId(recipientDiscordId: Long, channelId: Long? = null, messageId: Long? = null): String =
            if (channelId == null || messageId == null) {
                "$MODAL_NAME:$recipientDiscordId:"
            } else {
                "$MODAL_NAME:$recipientDiscordId::$channelId:$messageId"
            }

        /** `/tip` — the amount came in as an option, so only the note is asked for. */
        fun noteForm(recipientDiscordId: Long, amount: Long): JdaModal =
            JdaModal.create(customId(recipientDiscordId, amount), "Tip $amount credits")
                .addComponents(Label.of(noteLabel(), noteInput()))
                .build()

        /**
         * The right-click and button paths, which know who but not how much.
         *
         * The recipient's name goes in the title because a right-click is easy
         * to land on the wrong person, and the form is the last chance to
         * notice before credit moves.
         */
        fun amountForm(
            recipientDiscordId: Long,
            recipientName: String,
            channelId: Long? = null,
            messageId: Long? = null,
        ): JdaModal {
            val amountInput = TextInput.create(FIELD_AMOUNT, TextInputStyle.SHORT)
                .setPlaceholder("${TipService.MIN_TIP}–${TipService.MAX_TIP}")
                .setRequired(true)
                .setRequiredRange(1, TipService.MAX_TIP.toString().length)
                .build()
            return JdaModal.create(
                askAmountId(recipientDiscordId, channelId, messageId),
                truncate("Tip $recipientName"),
            )
                .addComponents(
                    Label.of("Credits (${TipService.MIN_TIP}–${TipService.MAX_TIP})", amountInput),
                    Label.of(noteLabel(), noteInput()),
                )
                .build()
        }

        private fun noteInput(): TextInput = TextInput.create(FIELD_NOTE, TextInputStyle.PARAGRAPH)
            .setPlaceholder("Optional note that ships with the tip.")
            .setRequired(false)
            .setRequiredRange(0, TipService.MAX_NOTE_LENGTH)
            .build()

        private fun noteLabel() = "Note (optional, max ${TipService.MAX_NOTE_LENGTH} chars)"

        private fun truncate(title: String): String =
            if (title.length <= TITLE_LIMIT) title else title.take(TITLE_LIMIT - 1).trimEnd() + "…"
    }
}
