package bot.toby.notify

import bot.toby.command.commands.economy.DuelEmbeds
import common.logging.DiscordLogger
import database.duel.PendingDuelRegistry
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import web.event.WebDuelOfferedEvent

/**
 * Posts a Discord channel notification when a duel is offered from
 * the web UI. The slash-command path posts inline to the channel
 * where `/duel` was invoked; web-initiated offers have no such
 * channel context, so this listener targets the guild's system
 * channel.
 *
 * Accept/Decline buttons resolve through the shared
 * [PendingDuelRegistry] regardless of which channel hosts the
 * message, so they Just Work. Unlike the slash-command path, this
 * notifier does NOT register an `onTimeout` callback — if the offer
 * expires before the opponent clicks, the message stays as-is and
 * the buttons go inert with the existing "already resolved or
 * expired" reply (same UX as a stale slash-command offer).
 */
@Component
class WebDuelOfferNotifier(
    private val jda: JDA,
    private val pendingDuelRegistry: PendingDuelRegistry,
) {
    private val logger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun on(event: WebDuelOfferedEvent) {
        val guild = jda.getGuildById(event.guildId) ?: run {
            logger.warn("WebDuelOfferedEvent for guild ${event.guildId} but bot is not in that guild; skipping.")
            return
        }
        val channel = resolveChannel(guild) ?: run {
            logger.warn("No writable system channel in guild ${event.guildId}; skipping web-duel notification.")
            return
        }
        val accept = Button.success(
            DuelEmbeds.acceptButtonId(event.duelId, event.opponentDiscordId),
            "Accept"
        )
        val decline = Button.danger(
            DuelEmbeds.declineButtonId(event.duelId, event.opponentDiscordId),
            "Decline"
        )
        // addContent on the message (not the embed description) so the
        // <@opponent> mention actually pings — embed-mention pings are silent.
        runCatching {
            channel.sendMessageEmbeds(
                DuelEmbeds.offerEmbed(
                    event.initiatorDiscordId,
                    event.opponentDiscordId,
                    event.stake,
                    pendingDuelRegistry.ttl
                )
            )
                .addContent("<@${event.opponentDiscordId}>")
                .addComponents(ActionRow.of(accept, decline))
                .queue()
        }.onFailure {
            logger.error("Could not post web-duel notification to ${channel.id}: ${it.message}")
        }
    }

    private fun resolveChannel(guild: Guild): TextChannel? {
        val bot = guild.selfMember
        return guild.systemChannel?.takeIf {
            bot.hasPermission(it, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
        }
    }
}
