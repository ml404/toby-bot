package bot.toby.command.commands.economy

import database.service.TipService.TipOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Shared embed builders for the `/tip` flow. Used by [TipCommand] for
 * the slash-command reply and by `WebTipNotifier` for the system-channel
 * notification when a tip is initiated from the web UI — both surfaces
 * render identical embeds so the recipient's experience is consistent.
 */
internal object TipEmbeds {

    private val OK_COLOR = Color(87, 242, 135)
    private val ERROR_COLOR = Color(237, 66, 69)

    fun okEmbed(outcome: TipOutcome.Ok): MessageEmbed = EmbedBuilder()
        .setTitle("💸 Tip sent")
        .setDescription(
            "<@${outcome.sender}> tipped <@${outcome.recipient}> **${outcome.amount} credits**." +
                outcome.note?.let { "\n*${it}*" }.orEmpty()
        )
        .addField("Sender balance", "${outcome.senderNewBalance} credits", true)
        .addField(
            "Sender tipped today",
            "${outcome.sentTodayAfter}/${outcome.dailyCap}",
            true
        )
        .setColor(OK_COLOR)
        .build()

    fun errorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("💸 Tip")
        .setDescription(message)
        .setColor(ERROR_COLOR)
        .build()
}
