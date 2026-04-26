package bot.toby.notify

import bot.toby.command.commands.economy.TipEmbeds
import common.logging.DiscordLogger
import database.service.TipService.TipOutcome
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import web.event.WebTipSentEvent

/**
 * Posts a Discord channel notification when a tip is initiated from
 * the web UI. The slash-command path posts inline to the channel
 * where `/tip` was invoked; web-initiated tips have no such channel
 * context, so this listener targets the guild's system channel.
 *
 * If the bot has no writable system channel, the post is skipped (the
 * underlying transfer already happened — this is best-effort
 * notification only).
 */
@Component
class WebTipNotifier(
    private val jda: JDA,
) {
    private val logger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun on(event: WebTipSentEvent) {
        val guild = jda.getGuildById(event.guildId) ?: run {
            logger.warn("WebTipSentEvent for guild ${event.guildId} but bot is not in that guild; skipping.")
            return
        }
        val channel = resolveChannel(guild) ?: run {
            logger.warn("No writable system channel in guild ${event.guildId}; skipping web-tip notification.")
            return
        }
        val outcome = TipOutcome.Ok(
            sender = event.senderDiscordId,
            recipient = event.recipientDiscordId,
            amount = event.amount,
            note = event.note,
            senderNewBalance = event.senderNewBalance,
            recipientNewBalance = event.recipientNewBalance,
            sentTodayAfter = event.sentTodayAfter,
            dailyCap = event.dailyCap
        )
        // addContent on the message (not the embed description) so the
        // <@recipient> mention actually pings — embed-mention pings are silent.
        runCatching {
            channel.sendMessageEmbeds(TipEmbeds.okEmbed(outcome))
                .addContent("<@${event.recipientDiscordId}>")
                .queue()
        }.onFailure {
            logger.error("Could not post web-tip notification to ${channel.id}: ${it.message}")
        }
    }

    private fun resolveChannel(guild: Guild): TextChannel? {
        val bot = guild.selfMember
        return guild.systemChannel?.takeIf {
            bot.hasPermission(it, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
        }
    }
}
