package bot.toby.button.buttons

import bot.toby.command.commands.game.PvpEmbeds
import bot.toby.command.commands.game.TicTacToeEmbeds
import common.tictactoe.TicTacToeEngine
import core.button.Button
import core.button.ButtonContext
import database.boardgame.TurnBasedBoardWagerService
import database.dto.UserDto
import database.service.pvp.PvpWagerService
import database.service.pvp.tictactoe.TicTacToeService
import database.tictactoe.TicTacToeSessionRegistry
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Routes every `/tictactoe` button click. The component ID prefix is
 * `"tictactoe"` — [bot.toby.managers.DefaultButtonManager] dispatches
 * by that prefix to this single bean, which parses the rest of the id
 * via [TicTacToeEmbeds.parseButtonId] and switches on the action.
 *
 * Race safety: every state transition on [TicTacToeSessionRegistry]
 * uses atomic remove (`decline`, `consumeForResolution`, `forfeit`)
 * or `synchronized` on the session (`accept`, `applyMove`), so
 * concurrent clicks / timeouts collapse to "exactly one path wins".
 */
@Component
class TicTacToeButton @Autowired constructor(
    private val ticTacToeService: TicTacToeService,
    private val ticTacToeSessionRegistry: TicTacToeSessionRegistry,
) : Button {

    override val name: String get() = TicTacToeEmbeds.BUTTON_NAME
    override val description: String get() = "Routes /tictactoe accept/decline + place + forfeit clicks."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = TicTacToeEmbeds.parseButtonId(event.componentId) ?: run {
            event.hook.sendMessage("Couldn't parse this Tic-Tac-Toe button.").setEphemeral(true).queue()
            return
        }
        when (parsed.action) {
            TicTacToeEmbeds.Action.ACCEPT -> handleAccept(event, requestingUserDto, parsed)
            TicTacToeEmbeds.Action.DECLINE -> handleDecline(event, requestingUserDto, parsed)
            TicTacToeEmbeds.Action.FORFEIT -> handleForfeit(event, requestingUserDto, parsed)
            else -> {
                val cell = TicTacToeEmbeds.cellFor(parsed.action) ?: run {
                    event.hook.sendMessage("Unknown action.").setEphemeral(true).queue()
                    return
                }
                handlePlace(event, requestingUserDto, parsed, cell)
            }
        }
    }

    private fun handleDecline(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: TicTacToeEmbeds.ParsedButtonId,
    ) = PvpButtonHelpers.handleDecline(
        event = event,
        requestingUserDto = requestingUserDto,
        scopedDiscordId = parsed.payload,
        sessionId = parsed.sessionId,
        decline = ticTacToeSessionRegistry::decline,
        pendingDeclineEmbed = TicTacToeEmbeds::pendingDeclineEmbed,
    )

    private fun handleAccept(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: TicTacToeEmbeds.ParsedButtonId,
    ) {
        if (!PvpButtonHelpers.requireScopedActor(event, requestingUserDto, parsed.payload, "accept")) return
        val session = ticTacToeSessionRegistry.accept(parsed.sessionId) { expired ->
            // Move-clock timeout: current actor never placed. Treat
            // as forfeit by them — opponent wins by walkover.
            resolveTimeout(event, expired)
        } ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }

        val outcome = ticTacToeService.acceptMatch(
            initiatorDiscordId = session.initiatorDiscordId,
            opponentDiscordId = session.opponentDiscordId,
            guildId = session.guildId,
            stake = session.stake,
        )
        if (outcome !is PvpWagerService.AcceptOutcome.Ok) {
            ticTacToeSessionRegistry.forfeit(session.id)
            event.message.editMessageEmbeds(PvpEmbeds.acceptErrorEmbed(PvpButtonHelpers.describeAccept(outcome)))
                .setComponents(emptyList<MessageTopLevelComponent>()).queue()
            return
        }

        event.message.editMessageEmbeds(TicTacToeEmbeds.turnEmbed(session))
            .setComponents(TicTacToeEmbeds.liveButtons(session)).queue()
    }

    private fun handlePlace(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: TicTacToeEmbeds.ParsedButtonId,
        cell: Int,
    ) {
        val live = ticTacToeSessionRegistry.get(parsed.sessionId) ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }
        if (!PvpButtonHelpers.requireMatchParticipant(
                event, requestingUserDto, live.initiatorDiscordId, live.opponentDiscordId,
                notParticipantMessage = "This isn't your match — only <@${live.initiatorDiscordId}> and <@${live.opponentDiscordId}> can play.",
            )
        ) return
        val result = ticTacToeSessionRegistry.applyMove(parsed.sessionId, requestingUserDto.discordId, cell) { expired ->
            resolveTimeout(event, expired)
        }
        when (result) {
            null -> {
                // Either: not their turn, session gone, or some race.
                event.hook.sendMessage("It's not your turn yet.").setEphemeral(true).queue()
            }
            TicTacToeEngine.MoveResult.IllegalCell,
            TicTacToeEngine.MoveResult.Occupied -> {
                event.hook.sendMessage("That cell is taken.").setEphemeral(true).queue()
            }
            is TicTacToeEngine.MoveResult.Continued -> {
                // Re-render with the new board + the *other* player's turn.
                val refreshed = ticTacToeSessionRegistry.get(parsed.sessionId) ?: return
                event.message.editMessageEmbeds(TicTacToeEmbeds.turnEmbed(refreshed))
                    .setComponents(TicTacToeEmbeds.liveButtons(refreshed)).queue()
            }
            is TicTacToeEngine.MoveResult.Win,
            is TicTacToeEngine.MoveResult.Draw -> {
                val consumed = ticTacToeSessionRegistry.consumeForResolution(parsed.sessionId) ?: return
                resolveAndEdit(event, consumed, forfeit = false)
            }
        }
    }

    private fun handleForfeit(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        parsed: TicTacToeEmbeds.ParsedButtonId,
    ) {
        val live = ticTacToeSessionRegistry.get(parsed.sessionId) ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }
        if (!PvpButtonHelpers.requireMatchParticipant(
                event, requestingUserDto, live.initiatorDiscordId, live.opponentDiscordId,
                notParticipantMessage = "This isn't your match to forfeit.",
            )
        ) return
        val consumed = ticTacToeSessionRegistry.forfeit(parsed.sessionId) ?: run {
            PvpButtonHelpers.ephemeralAlreadyResolved(event); return
        }
        // Stamp the winner: the *other* player wins by walkover.
        val winnerDiscordId = if (requestingUserDto.discordId == consumed.initiatorDiscordId)
            consumed.opponentDiscordId else consumed.initiatorDiscordId
        consumed.winner = consumed.markFor(winnerDiscordId)
        resolveAndEdit(event, consumed, forfeit = true, explicitWinnerDiscordId = winnerDiscordId)
    }

    private fun resolveTimeout(event: ButtonInteractionEvent, expired: TicTacToeSessionRegistry.Session) {
        // Whoever's turn it was timed out — opponent wins by walkover.
        val winnerDiscordId = if (expired.currentActorDiscordId() == expired.initiatorDiscordId)
            expired.opponentDiscordId else expired.initiatorDiscordId
        expired.winner = expired.markFor(winnerDiscordId)
        resolveAndEdit(event, expired, forfeit = true, explicitWinnerDiscordId = winnerDiscordId)
    }

    private fun resolveAndEdit(
        event: ButtonInteractionEvent,
        session: TicTacToeSessionRegistry.Session,
        forfeit: Boolean,
        explicitWinnerDiscordId: Long? = null,
    ) {
        // Determine the winner: explicit (forfeit / timeout) or derived
        // from the session.winner that the engine stamped at terminal.
        val winnerDiscordId = explicitWinnerDiscordId
            ?: session.winner?.let {
                when (it) {
                    TicTacToeEngine.Mark.X -> session.initiatorDiscordId
                    TicTacToeEngine.Mark.O -> session.opponentDiscordId
                }
            }
        val outcome = ticTacToeService.resolveMatch(
            initiatorDiscordId = session.initiatorDiscordId,
            opponentDiscordId = session.opponentDiscordId,
            guildId = session.guildId,
            stake = session.stake,
            winnerDiscordId = winnerDiscordId,
        )
        val embed: MessageEmbed = when (outcome) {
            is TurnBasedBoardWagerService.ResolveOutcome.Win -> TicTacToeEmbeds.winEmbed(session, outcome, forfeit)
            is TurnBasedBoardWagerService.ResolveOutcome.Draw -> TicTacToeEmbeds.drawEmbed(session, outcome)
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
