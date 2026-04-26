package bot.toby.button.buttons

import bot.toby.command.commands.economy.DuelEmbeds
import database.duel.PendingDuelRegistry
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.service.DuelService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves a `/duel` offer once the opponent clicks Accept or Decline.
 * The duel id and the opponent's discord id are encoded in the
 * component ID, so this handler doesn't need to look up the offer by
 * any other key.
 *
 * Race-safety: [PendingDuelRegistry.consumeForAccept] and
 * [PendingDuelRegistry.cancel] both use atomic remove. Double-clicks,
 * accept-vs-decline races, and timeout-vs-click races all collapse to
 * "exactly one path sees the offer; everyone else's branch logs
 * 'offer already resolved or expired'".
 */
@Component
class DuelButton @Autowired constructor(
    private val duelService: DuelService,
    private val pendingDuelRegistry: PendingDuelRegistry,
) : Button {

    override val name: String get() = DuelEmbeds.BUTTON_NAME
    override val description: String get() = "Resolves a /duel offer in the opponent's chosen direction."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = DuelEmbeds.parseButtonId(event.componentId) ?: run {
            event.hook.sendMessage("Couldn't parse this duel button.").setEphemeral(true).queue()
            return
        }

        if (requestingUserDto.discordId != parsed.opponentDiscordId) {
            event.hook.sendMessage(
                "This isn't your duel offer — wait for the challenged user to respond, " +
                    "or run `/duel` to start your own."
            ).setEphemeral(true).queue()
            return
        }

        when (parsed.action) {
            DuelEmbeds.Action.DECLINE -> handleDecline(event, parsed)
            DuelEmbeds.Action.ACCEPT -> handleAccept(event, parsed)
        }
    }

    private fun handleDecline(
        event: net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent,
        parsed: DuelEmbeds.ParsedButtonId,
    ) {
        val offer = pendingDuelRegistry.cancel(parsed.duelId)
        if (offer == null) {
            event.hook.sendMessage("This duel offer already resolved or expired.").setEphemeral(true).queue()
            return
        }
        event.message.editMessageEmbeds(
            DuelEmbeds.declineEmbed(offer.initiatorDiscordId, offer.opponentDiscordId, offer.stake)
        ).setComponents(emptyList()).queue()
    }

    private fun handleAccept(
        event: net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent,
        parsed: DuelEmbeds.ParsedButtonId,
    ) {
        val offer = pendingDuelRegistry.consumeForAccept(parsed.duelId)
        if (offer == null) {
            event.hook.sendMessage("This duel offer already resolved or expired.").setEphemeral(true).queue()
            return
        }

        val outcome = duelService.acceptDuel(
            initiatorDiscordId = offer.initiatorDiscordId,
            opponentDiscordId = offer.opponentDiscordId,
            guildId = offer.guildId,
            stake = offer.stake
        )
        when (outcome) {
            is DuelService.AcceptOutcome.Win -> {
                event.message.editMessageEmbeds(DuelEmbeds.winEmbed(outcome))
                    .setComponents(emptyList()).queue()
            }
            else -> {
                event.message.editMessageEmbeds(DuelEmbeds.acceptErrorEmbed(outcome))
                    .setComponents(emptyList()).queue()
            }
        }
    }
}
