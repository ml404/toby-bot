package bot.toby.button.buttons

import bot.toby.command.commands.economy.RpsEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.rps.RpsSessionRegistry
import database.service.RpsService
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Routes every `/rps` button click. The component ID prefix is
 * `"rps"` — [bot.toby.managers.DefaultButtonManager] dispatches by
 * that prefix to this single bean, which parses the rest of the id
 * via [RpsEmbeds.parseButtonId] and switches on the action.
 *
 * Race safety: every state transition on [RpsSessionRegistry] uses
 * atomic remove (`decline`, `consumeForResolution`, `forfeit`) or
 * `synchronized` on the session object (`accept`, `recordPick`), so
 * concurrent clicks / timeouts collapse to "exactly one path wins".
 */
@Component
class RpsButton @Autowired constructor(
    private val rpsService: RpsService,
    private val rpsSessionRegistry: RpsSessionRegistry,
) : Button {

    override val name: String get() = RpsEmbeds.BUTTON_NAME
    override val description: String get() = "Routes /rps accept/decline + pick + forfeit clicks."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = RpsEmbeds.parseButtonId(event.componentId) ?: run {
            event.hook.sendMessage("Couldn't parse this RPS button.").setEphemeral(true).queue()
            return
        }
        when (parsed.action) {
            RpsEmbeds.Action.DECLINE -> handleDecline(event, requestingUserDto, parsed)
            RpsEmbeds.Action.ACCEPT -> handleAccept(event, requestingUserDto, parsed)
            RpsEmbeds.Action.PICK_ROCK,
            RpsEmbeds.Action.PICK_PAPER,
            RpsEmbeds.Action.PICK_SCISSORS -> handlePick(event, requestingUserDto, parsed)
            RpsEmbeds.Action.FORFEIT -> handleForfeit(event, requestingUserDto, parsed)
        }
    }

    private fun handleDecline(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: RpsEmbeds.ParsedButtonId,
    ) {
        if (requestingUserDto.discordId != parsed.scopedDiscordId) {
            event.hook.sendMessage(
                "This isn't your challenge to decline — wait for <@${parsed.scopedDiscordId}> to respond."
            ).setEphemeral(true).queue()
            return
        }
        val session = rpsSessionRegistry.decline(parsed.sessionId) ?: run {
            ephemeralAlreadyResolved(event); return
        }
        event.message.editMessageEmbeds(
            RpsEmbeds.pendingDeclineEmbed(session.initiatorDiscordId, session.opponentDiscordId)
        ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
    }

    private fun handleAccept(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: RpsEmbeds.ParsedButtonId,
    ) {
        if (requestingUserDto.discordId != parsed.scopedDiscordId) {
            event.hook.sendMessage(
                "This isn't your challenge to accept — wait for <@${parsed.scopedDiscordId}> to respond."
            ).setEphemeral(true).queue()
            return
        }
        // Transition PENDING → LIVE and schedule the pick-phase timeout
        // before we touch the user table. If accept() returns null the
        // session expired or was declined between the click and now.
        val session = rpsSessionRegistry.accept(parsed.sessionId) { expired ->
            // Pick-phase timeout: someone picked or no one did. Resolve
            // through the service so the wager arithmetic in one place
            // handles all four branches.
            resolveAndEdit(event, expired)
        } ?: run {
            ephemeralAlreadyResolved(event); return
        }

        // Debit both stakes now (no-op for stake=0). If insufficient,
        // the match doesn't go LIVE — refund the LIVE state by removing
        // the session and surface an error.
        val outcome = rpsService.acceptMatch(
            initiatorDiscordId = session.initiatorDiscordId,
            opponentDiscordId = session.opponentDiscordId,
            guildId = session.guildId,
            stake = session.stake,
        )
        if (outcome !is RpsService.AcceptOutcome.Ok) {
            rpsSessionRegistry.forfeit(session.id) // tear down the LIVE session
            event.message.editMessageEmbeds(RpsEmbeds.acceptErrorEmbed(describeAccept(outcome)))
                .setComponents(emptyList<MessageTopLevelComponent>()).queue()
            return
        }

        // Edit the message to the pick-phase view.
        event.message.editMessageEmbeds(
            RpsEmbeds.pickEmbed(
                initiatorDiscordId = session.initiatorDiscordId,
                opponentDiscordId = session.opponentDiscordId,
                stake = session.stake,
                initiatorPicked = false,
                opponentPicked = false,
            )
        ).setComponents(RpsEmbeds.pickButtons(session.id)).queue()
    }

    private fun handlePick(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: RpsEmbeds.ParsedButtonId,
    ) {
        val choice = RpsEmbeds.choiceFor(parsed.action) ?: return
        val live = rpsSessionRegistry.get(parsed.sessionId) ?: run {
            ephemeralAlreadyResolved(event); return
        }
        if (requestingUserDto.discordId != live.initiatorDiscordId &&
            requestingUserDto.discordId != live.opponentDiscordId
        ) {
            event.hook.sendMessage(
                "This isn't your match — only <@${live.initiatorDiscordId}> and <@${live.opponentDiscordId}> can pick."
            ).setEphemeral(true).queue()
            return
        }
        val updated = rpsSessionRegistry.recordPick(parsed.sessionId, requestingUserDto.discordId, choice) ?: run {
            ephemeralAlreadyResolved(event); return
        }
        // Confirm the pick to the player privately so the opponent
        // doesn't see what they chose.
        event.hook.sendMessage("You picked **${RpsEmbeds.prettyChoice(choice)}**.")
            .setEphemeral(true).queue()

        if (updated.bothPicked) {
            // Drain atomically so the timeout callback can't double-fire.
            val consumed = rpsSessionRegistry.consumeForResolution(parsed.sessionId) ?: return
            resolveAndEdit(event, consumed)
        } else {
            // Re-render the pick embed so the *other* player sees a
            // "✅ picked" indicator on the waiting line.
            event.message.editMessageEmbeds(
                RpsEmbeds.pickEmbed(
                    initiatorDiscordId = updated.initiatorDiscordId,
                    opponentDiscordId = updated.opponentDiscordId,
                    stake = updated.stake,
                    initiatorPicked = updated.picks.containsKey(updated.initiatorDiscordId),
                    opponentPicked = updated.picks.containsKey(updated.opponentDiscordId),
                )
            ).queue()
        }
    }

    private fun handleForfeit(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: RpsEmbeds.ParsedButtonId,
    ) {
        val live = rpsSessionRegistry.get(parsed.sessionId) ?: run {
            ephemeralAlreadyResolved(event); return
        }
        if (requestingUserDto.discordId != live.initiatorDiscordId &&
            requestingUserDto.discordId != live.opponentDiscordId
        ) {
            event.hook.sendMessage(
                "This isn't your match to forfeit."
            ).setEphemeral(true).queue()
            return
        }
        // Forfeit = the other player wins by walkover. Atomically remove
        // the session so the pick-timeout can't double-resolve.
        val consumed = rpsSessionRegistry.forfeit(parsed.sessionId) ?: run {
            ephemeralAlreadyResolved(event); return
        }
        // Strip the forfeiter's pick from the snapshot so the resolver
        // sees them as "didn't pick" → opponent wins.
        consumed.picks.remove(requestingUserDto.discordId)
        resolveAndEdit(event, consumed)
    }

    private fun resolveAndEdit(event: ButtonInteractionEvent, session: RpsSessionRegistry.Session) {
        val outcome = rpsService.resolveMatch(
            initiatorDiscordId = session.initiatorDiscordId,
            opponentDiscordId = session.opponentDiscordId,
            guildId = session.guildId,
            stake = session.stake,
            initiatorChoice = session.picks[session.initiatorDiscordId],
            opponentChoice = session.picks[session.opponentDiscordId],
        )
        val embed: MessageEmbed = when (outcome) {
            is RpsService.ResolveOutcome.Win -> RpsEmbeds.winEmbed(outcome)
            is RpsService.ResolveOutcome.Draw -> RpsEmbeds.drawEmbed(
                outcome, session.initiatorDiscordId, session.opponentDiscordId,
            )
            is RpsService.ResolveOutcome.DoubleRefund -> RpsEmbeds.doubleRefundEmbed(
                session.initiatorDiscordId, session.opponentDiscordId, outcome.stake,
            )
            else -> RpsEmbeds.acceptErrorEmbed("Couldn't resolve the match — both players' profiles must exist.")
        }
        runCatching {
            event.message.editMessageEmbeds(embed)
                .setComponents(emptyList<MessageTopLevelComponent>()).queue()
        }
    }

    private fun ephemeralAlreadyResolved(event: ButtonInteractionEvent) {
        event.hook.sendMessage("This match already resolved or expired.")
            .setEphemeral(true).queue()
    }

    private fun describeAccept(outcome: RpsService.AcceptOutcome): String = when (outcome) {
        is RpsService.AcceptOutcome.InitiatorInsufficient ->
            "The challenger no longer has enough credits to cover the stake."
        is RpsService.AcceptOutcome.OpponentInsufficient ->
            "You no longer have enough credits (have ${outcome.have}, need ${outcome.needed})."
        RpsService.AcceptOutcome.UnknownInitiator ->
            "We couldn't find the challenger's profile."
        RpsService.AcceptOutcome.UnknownOpponent ->
            "We couldn't find your profile."
        is RpsService.AcceptOutcome.Ok -> "" // never surfaced
    }
}
