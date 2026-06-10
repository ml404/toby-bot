package bot.toby.notify

import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushPayload
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import web.event.WebPvpChallengeEvent

/**
 * Posts a Discord channel notification when an RPS / tic-tac-toe /
 * connect-4 challenge is created from the web UI or the casino
 * activity. Counterpart to [WebDuelOfferNotifier], with one deliberate
 * difference: duels carry Discord-side Accept/Decline buttons (the
 * resolution is a single coin flip), while these games are played out
 * on the web/activity board — so the message is a ping plus a pointer
 * back into the casino, not an interactive offer.
 *
 * Channel choice: the SYSTEM route with the participants' current
 * voice channel as the origin hint. When the challenge comes from the
 * activity, everyone involved is sitting in a voice channel — its text
 * chat is where the ping is actually seen. Opponent's channel wins
 * over the initiator's (the ping is for them); with neither in voice
 * the router falls back to the guild's system channel as before.
 */
@Component
class WebPvpChallengeNotifier(
    private val notificationRouter: NotificationRouter,
    private val jda: JDA,
    @Value("\${app.base-url:}") private val webBaseUrl: String = "",
) {

    @EventListener
    fun on(event: WebPvpChallengeEvent) {
        notificationRouter.dispatch(
            kind = NotificationChannelKind.PVP_CHALLENGE,
            discordId = event.opponentDiscordId,
            guildId = event.guildId,
        ) {
            channel(
                route = ChannelRouteKey.SYSTEM,
                originChannelId = jda.currentVoiceChannelId(
                    event.guildId,
                    event.opponentDiscordId,
                    event.initiatorDiscordId,
                ),
                // Router suppresses the opponent's ping when they've opted
                // out of (PVP_CHALLENGE, CHANNEL); the message still posts.
                mentions = ChannelMentions(
                    kind = NotificationChannelKind.PVP_CHALLENGE,
                    userIds = listOf(event.opponentDiscordId),
                ),
            ) {
                MessageCreateBuilder()
                    .setContent(
                        "⚔️ <@${event.opponentDiscordId}> — <@${event.initiatorDiscordId}> challenged you to " +
                            "${event.gameLabel} for ${event.stake} credits! " +
                            "Open the TobyBot casino (web or the Discord activity) to respond before it expires."
                    )
                    .build()
            }
            push {
                PushPayload(
                    title = "⚔️ ${event.gameLabel} challenge (${event.stake} credits)",
                    body = "<@${event.initiatorDiscordId}> challenged you. Respond before it expires.",
                    deepLink = webBaseUrl.takeIf { it.isNotBlank() }
                        ?.let { "$it/pvp/${event.guildId}" },
                )
            }
        }
    }
}
