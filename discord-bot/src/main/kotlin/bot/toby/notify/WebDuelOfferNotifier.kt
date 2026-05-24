package bot.toby.notify

import bot.toby.command.commands.game.pvp.duel.DuelEmbeds
import common.logging.DiscordLogger
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushPayload
import database.configuration.RegistryScheduler
import database.duel.PendingDuelRegistry
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import web.event.WebDuelOfferedEvent
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Posts a Discord channel notification when a duel is offered from
 * the web UI. The slash-command path posts inline to the channel
 * where `/duel` was invoked; web-initiated offers have no such
 * channel context, so this listener routes through the
 * [SYSTEM][ChannelRouteKey.SYSTEM] route via [NotificationRouter].
 *
 * Accept/Decline buttons resolve through the shared
 * [PendingDuelRegistry] regardless of which channel hosts the
 * message. The slash-command path passes an `onTimeout` callback to
 * the registry to clean up its (ephemeral-interaction-hook-backed)
 * message when the TTL fires. For web offers there's no interaction
 * hook to edit through, so the notifier owns its own cleanup:
 * captures the sent message via the router's `onSent` callback, then
 * schedules a TTL-aligned task that atomically claims the offer via
 * [PendingDuelRegistry.cancel] and — if it wins the race against
 * accept/decline — edits the embed to [DuelEmbeds.timeoutEmbed] and
 * strips the action row so the dead Accept/Decline buttons go away.
 */
@Component
class WebDuelOfferNotifier(
    private val notificationRouter: NotificationRouter,
    private val pendingDuelRegistry: PendingDuelRegistry,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    @Value("\${app.base-url:}") private val webBaseUrl: String = "",
) {
    private val logger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun on(event: WebDuelOfferedEvent) {
        val accept = Button.success(
            DuelEmbeds.acceptButtonId(event.duelId, event.opponentDiscordId),
            "Accept"
        )
        val decline = Button.danger(
            DuelEmbeds.declineButtonId(event.duelId, event.opponentDiscordId),
            "Decline"
        )
        notificationRouter.dispatch(
            kind = NotificationChannelKind.DUEL_OFFER,
            discordId = event.opponentDiscordId,
            guildId = event.guildId,
        ) {
            channel(
                route = ChannelRouteKey.SYSTEM,
                onSent = { sent -> scheduleTimeoutCleanup(event, sent) },
                // Router suppresses the opponent's user-ping when they've
                // opted out of (DUEL_OFFER, CHANNEL). Buttons still render
                // and the embed still shows; they just don't get notified.
                mentions = ChannelMentions(
                    kind = NotificationChannelKind.DUEL_OFFER,
                    userIds = listOf(event.opponentDiscordId),
                ),
            ) {
                // setContent on the message (not the embed description) so the
                // <@opponent> mention actually pings — embed-mention pings are silent.
                MessageCreateBuilder()
                    .setEmbeds(
                        DuelEmbeds.offerEmbed(
                            event.initiatorDiscordId,
                            event.opponentDiscordId,
                            event.stake,
                            pendingDuelRegistry.ttl,
                        )
                    )
                    .setContent("<@${event.opponentDiscordId}>")
                    .setComponents(ActionRow.of(accept, decline))
                    .build()
            }
            push {
                PushPayload(
                    title = "⚔️ Duel offer (${event.stake} credits)",
                    body = "<@${event.initiatorDiscordId}> challenged you. Respond before it expires.",
                    deepLink = webBaseUrl.takeIf { it.isNotBlank() }
                        ?.let { "$it/profile/${event.guildId}" },
                )
            }
        }
    }

    private fun scheduleTimeoutCleanup(event: WebDuelOfferedEvent, sent: Message) {
        val channel: MessageChannel = sent.channel
        scheduler.schedule({
            // Atomic claim — if cancel returns null the offer was already
            // accepted/declined and DuelButton already edited the message;
            // we silently no-op so we don't clobber the result embed.
            val expired = pendingDuelRegistry.cancel(event.duelId) ?: return@schedule
            runCatching {
                channel.editMessageEmbedsById(
                    sent.id,
                    DuelEmbeds.timeoutEmbed(
                        expired.initiatorDiscordId,
                        expired.opponentDiscordId,
                        expired.stake
                    )
                ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
            }.onFailure {
                logger.error("Could not edit expired web-duel message ${sent.id} in ${channel.id}: ${it.message}")
            }
        }, pendingDuelRegistry.ttl.toMillis(), TimeUnit.MILLISECONDS)
    }
}
