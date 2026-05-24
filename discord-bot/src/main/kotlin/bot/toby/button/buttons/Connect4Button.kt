package bot.toby.button.buttons

import bot.toby.command.commands.game.Connect4Embeds
import bot.toby.command.commands.game.PvpEmbeds
import common.connect4.Connect4Engine
import core.button.Button
import core.button.ButtonContext
import database.boardgame.TurnBasedBoardWagerService
import database.connect4.Connect4SessionRegistry
import database.dto.UserDto
import database.service.Connect4Service
import database.service.PvpWagerService
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Routes every `/connect4` button click. The component ID prefix is
 * `"connect4"` — [bot.toby.managers.DefaultButtonManager] dispatches
 * by that prefix to this single bean, which parses the rest of the id
 * via [Connect4Embeds.parseButtonId] and switches on the action.
 *
 * Race safety: every state transition on [Connect4SessionRegistry]
 * uses atomic remove (`decline`, `consumeForResolution`, `forfeit`)
 * or `synchronized` on the session (`accept`, `applyMove`), so
 * concurrent clicks / timeouts collapse to "exactly one path wins".
 */
@Component
class Connect4Button @Autowired constructor(
    private val connect4Service: Connect4Service,
    private val connect4SessionRegistry: Connect4SessionRegistry,
) : Button {

    override val name: String get() = Connect4Embeds.BUTTON_NAME
    override val description: String get() = "Routes /connect4 accept/decline + drop + forfeit clicks."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = Connect4Embeds.parseButtonId(event.componentId) ?: run {
            event.hook.sendMessage("Couldn't parse this Connect 4 button.").setEphemeral(true).queue()
            return
        }
        when (parsed.action) {
            Connect4Embeds.Action.ACCEPT -> handleAccept(event, requestingUserDto, parsed)
            Connect4Embeds.Action.DECLINE -> handleDecline(event, requestingUserDto, parsed)
            Connect4Embeds.Action.FORFEIT -> handleForfeit(event, requestingUserDto, parsed)
            else -> {
                val column = Connect4Embeds.columnFor(parsed.action) ?: run {
                    event.hook.sendMessage("Unknown action.").setEphemeral(true).queue()
                    return
                }
                handleDrop(event, requestingUserDto, parsed, column)
            }
        }
    }

    private fun handleDecline(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: Connect4Embeds.ParsedButtonId,
    ) {
        if (requestingUserDto.discordId != parsed.payload) {
            event.hook.sendMessage(
                "This isn't your challenge to decline — wait for <@${parsed.payload}> to respond."
            ).setEphemeral(true).queue()
            return
        }
        val session = connect4SessionRegistry.decline(parsed.sessionId) ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }
        event.message.editMessageEmbeds(
            Connect4Embeds.pendingDeclineEmbed(session.initiatorDiscordId, session.opponentDiscordId)
        ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
    }

    private fun handleAccept(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: Connect4Embeds.ParsedButtonId,
    ) {
        if (requestingUserDto.discordId != parsed.payload) {
            event.hook.sendMessage(
                "This isn't your challenge to accept — wait for <@${parsed.payload}> to respond."
            ).setEphemeral(true).queue()
            return
        }
        val session = connect4SessionRegistry.accept(parsed.sessionId) { expired ->
            // Move-clock timeout: current actor never dropped. Treat
            // as forfeit by them — opponent wins by walkover.
            resolveTimeout(event, expired)
        } ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }

        val outcome = connect4Service.acceptMatch(
            initiatorDiscordId = session.initiatorDiscordId,
            opponentDiscordId = session.opponentDiscordId,
            guildId = session.guildId,
            stake = session.stake,
        )
        if (outcome !is PvpWagerService.AcceptOutcome.Ok) {
            connect4SessionRegistry.forfeit(session.id)
            event.message.editMessageEmbeds(PvpEmbeds.acceptErrorEmbed(PvpButtonHelpers.describeAccept(outcome)))
                .setComponents(emptyList<MessageTopLevelComponent>()).queue()
            return
        }

        event.message.editMessageEmbeds(Connect4Embeds.turnEmbed(session))
            .setComponents(Connect4Embeds.liveButtons(session)).queue()
    }

    private fun handleDrop(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: Connect4Embeds.ParsedButtonId,
        column: Int,
    ) {
        val live = connect4SessionRegistry.get(parsed.sessionId) ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }
        if (requestingUserDto.discordId != live.initiatorDiscordId &&
            requestingUserDto.discordId != live.opponentDiscordId
        ) {
            event.hook.sendMessage(
                "This isn't your match — only <@${live.initiatorDiscordId}> and <@${live.opponentDiscordId}> can play."
            ).setEphemeral(true).queue()
            return
        }
        val result = connect4SessionRegistry.applyMove(parsed.sessionId, requestingUserDto.discordId, column) { expired ->
            resolveTimeout(event, expired)
        }
        when (result) {
            null -> {
                event.hook.sendMessage("It's not your turn yet.").setEphemeral(true).queue()
            }
            Connect4Engine.MoveResult.InvalidColumn -> {
                event.hook.sendMessage("Invalid column.").setEphemeral(true).queue()
            }
            Connect4Engine.MoveResult.ColumnFull -> {
                event.hook.sendMessage("That column is full.").setEphemeral(true).queue()
            }
            is Connect4Engine.MoveResult.Continued -> {
                val refreshed = connect4SessionRegistry.get(parsed.sessionId) ?: return
                event.message.editMessageEmbeds(Connect4Embeds.turnEmbed(refreshed))
                    .setComponents(Connect4Embeds.liveButtons(refreshed)).queue()
            }
            is Connect4Engine.MoveResult.Win,
            is Connect4Engine.MoveResult.Draw -> {
                val consumed = connect4SessionRegistry.consumeForResolution(parsed.sessionId) ?: return
                resolveAndEdit(event, consumed, forfeit = false)
            }
        }
    }

    private fun handleForfeit(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: Connect4Embeds.ParsedButtonId,
    ) {
        val live = connect4SessionRegistry.get(parsed.sessionId) ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }
        if (requestingUserDto.discordId != live.initiatorDiscordId &&
            requestingUserDto.discordId != live.opponentDiscordId
        ) {
            event.hook.sendMessage("This isn't your match to forfeit.").setEphemeral(true).queue()
            return
        }
        val consumed = connect4SessionRegistry.forfeit(parsed.sessionId) ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }
        // Stamp the winner: the *other* player wins by walkover.
        val winnerDiscordId = if (requestingUserDto.discordId == consumed.initiatorDiscordId)
            consumed.opponentDiscordId else consumed.initiatorDiscordId
        consumed.winner = consumed.markFor(winnerDiscordId)
        resolveAndEdit(event, consumed, forfeit = true, explicitWinnerDiscordId = winnerDiscordId)
    }

    private fun resolveTimeout(event: ButtonInteractionEvent, expired: Connect4SessionRegistry.Session) {
        // Whoever's turn it was timed out — opponent wins by walkover.
        val winnerDiscordId = if (expired.currentActorDiscordId() == expired.initiatorDiscordId)
            expired.opponentDiscordId else expired.initiatorDiscordId
        expired.winner = expired.markFor(winnerDiscordId)
        resolveAndEdit(event, expired, forfeit = true, explicitWinnerDiscordId = winnerDiscordId)
    }

    private fun resolveAndEdit(
        event: ButtonInteractionEvent,
        session: Connect4SessionRegistry.Session,
        forfeit: Boolean,
        explicitWinnerDiscordId: Long? = null,
    ) {
        // Determine the winner: explicit (forfeit / timeout) or derived
        // from the session.winner that the engine stamped at terminal.
        val winnerDiscordId = explicitWinnerDiscordId
            ?: session.winner?.let {
                when (it) {
                    Connect4Engine.Mark.RED -> session.initiatorDiscordId
                    Connect4Engine.Mark.YELLOW -> session.opponentDiscordId
                }
            }
        val outcome = connect4Service.resolveMatch(
            initiatorDiscordId = session.initiatorDiscordId,
            opponentDiscordId = session.opponentDiscordId,
            guildId = session.guildId,
            stake = session.stake,
            winnerDiscordId = winnerDiscordId,
        )
        val embed: MessageEmbed = when (outcome) {
            is TurnBasedBoardWagerService.ResolveOutcome.Win -> Connect4Embeds.winEmbed(session, outcome, forfeit)
            is TurnBasedBoardWagerService.ResolveOutcome.Draw -> Connect4Embeds.drawEmbed(session, outcome)
            TurnBasedBoardWagerService.ResolveOutcome.Unknown -> PvpEmbeds.acceptErrorEmbed(
                "Couldn't resolve the match — both players' profiles must exist."
            )
        }
        runCatching {
            event.message.editMessageEmbeds(embed)
                .setComponents(emptyList<MessageTopLevelComponent>()).queue()
        }
    }
}
