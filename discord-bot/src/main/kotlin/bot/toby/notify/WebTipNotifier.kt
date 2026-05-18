package bot.toby.notify

import bot.toby.command.commands.economy.TipEmbeds
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import database.service.TipService.TipOutcome
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import web.event.WebTipSentEvent

/**
 * Posts a Discord channel notification when a tip is initiated from
 * the web UI. The slash-command path posts inline to the channel
 * where `/tip` was invoked; web-initiated tips have no such channel
 * context, so this listener routes through the [SYSTEM][ChannelRouteKey.SYSTEM]
 * route (system channel + permission checks live in [NotificationRouter]).
 *
 * If the bot has no writable system channel, the post is skipped (the
 * underlying transfer already happened — this is best-effort
 * notification only).
 */
@Component
class WebTipNotifier(
    private val notificationRouter: NotificationRouter,
) {
    @EventListener
    fun on(event: WebTipSentEvent) {
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
        notificationRouter.sendChannel(
            guildId = event.guildId,
            route = ChannelRouteKey.SYSTEM,
            message = {
                // setContent on the message (not the embed description) so the
                // <@recipient> mention actually pings — embed-mention pings are silent.
                MessageCreateBuilder()
                    .setEmbeds(TipEmbeds.okEmbed(outcome))
                    .setContent("<@${event.recipientDiscordId}>")
                    .build()
            },
            // Router suppresses the recipient's user-ping when they've
            // opted out of (TIP_RECEIVED, CHANNEL). Post still happens.
            mentions = ChannelMentions(
                kind = NotificationChannelKind.TIP_RECEIVED,
                userIds = listOf(event.recipientDiscordId),
            ),
        )
    }
}
