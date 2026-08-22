package bot.toby.intro

import bot.toby.notify.NotificationRouter
import common.discord.embed
import common.logging.DiscordLogger
import common.notification.ChannelRouteKey
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.awt.Color

/**
 * Says out loud that the audio source is refusing us.
 *
 * During an outage every intro on every server is silent, and those failures
 * are deliberately not counted against anybody's row — a rate-limit episode
 * marking everyone's working links dead would be worse than the outage. The
 * cost was that the one time *everybody's* intro went quiet was the one time
 * nothing explained it: no DM, no counter, and every surface a member could
 * reach still describing their intro as fine. The only thing in the product
 * that knew was `/introstats`, which is super-user only.
 *
 * Deliberately blames the bot rather than the listener. "Nothing found for
 * that link" was the sentence people got all evening during the episode this
 * came from, and it sent them off to re-upload intros that had never been
 * broken.
 */
@Service
class IntroOutageAnnouncer(
    // Nullable so unit tests and any non-Spring construction path degrade to
    // a no-op rather than an NPE, matching the other intro services.
    private val notificationRouter: NotificationRouter? = null,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun onSourceOutageNoticed(event: SourceOutageNoticedEvent) {
        val router = notificationRouter ?: return
        // PlayerManager latches this to once per guild per episode, so there
        // is no rate limiting to do here — during an outage this would
        // otherwise fire on every single voice join.
        runCatching {
            router.sendChannel(
                guildId = event.guildId,
                route = ChannelRouteKey.INTRO_OUTAGE,
                message = { MessageCreateBuilder().setEmbeds(outageEmbed()).build() },
            )
        }.onFailure {
            logger.error("Could not announce the source outage in guild ${event.guildId}: ${it.message}")
        }
    }

    private fun outageEmbed() = embed(
        title = "Intros are quiet for a bit",
        color = OUTAGE_COLOR,
        description = "The site TobyBot streams from is refusing us at the moment, so intros " +
            "aren't playing on voice joins.\n\n" +
            "**Nobody's intro is broken** — there's nothing to fix and nothing to re-upload. " +
            "It usually clears on its own within a few minutes.",
    ) {
        setFooter("Intro health is paused while this lasts, so nothing gets marked broken.")
    }

    companion object {
        /** Amber, matching the outage banner in `/introstats`. */
        private val OUTAGE_COLOR: Color = Color(250, 166, 26)
    }
}
