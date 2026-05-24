package bot.toby.command.commands.game.pvp.tictactoe

import common.tictactoe.TicTacToeEngine
import database.boardgame.TurnBasedBoardWagerService
import database.tictactoe.TicTacToeSessionRegistry
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.MessageEmbed
import bot.toby.command.commands.game.pvp.PvpButtonIdCodec
import bot.toby.command.commands.game.pvp.PvpEmbeds

/**
 * Embed + button rendering specific to `/tictactoe`. The cross-game
 * bits (Accept/Decline button row, stake-line text, error / decline /
 * timeout embeds, start-error description) live in [PvpEmbeds] —
 * this file only owns the TTT-specific board rendering, cell buttons,
 * and turn / win / draw embeds.
 *
 * Component IDs are colon-delimited so [DefaultButtonManager] can
 * route by the `"tictactoe"` prefix and this object can parse the
 * rest by index:
 *
 *   `tictactoe:<action>:<sessionId>:<scopedDiscordIdOrCell>`
 *
 * - `action`: ACCEPT / DECLINE during PENDING; PLACE_<0..8> /
 *   FORFEIT during LIVE.
 * - `scopedDiscordIdOrCell`: the opponent's discord id for
 *   ACCEPT/DECLINE; the cell index for PLACE_*; 0 for FORFEIT.
 */
object TicTacToeEmbeds {

    const val BUTTON_NAME = "tictactoe"
    private const val GAME_NAME = "Tic-Tac-Toe"

    private const val X_EMOJI = "❌"
    private const val O_EMOJI = "⭕"
    private const val EMPTY_EMOJI = "⬜"
    private const val WIN_X_EMOJI = "🟥"
    private const val WIN_O_EMOJI = "🟦"

    enum class Action {
        ACCEPT,
        DECLINE,
        PLACE_0, PLACE_1, PLACE_2,
        PLACE_3, PLACE_4, PLACE_5,
        PLACE_6, PLACE_7, PLACE_8,
        FORFEIT,
    }

    data class ParsedButtonId(
        val action: Action,
        val sessionId: Long,
        /** Discord id for ACCEPT/DECLINE; cell index for PLACE_*; 0 for FORFEIT. */
        val payload: Long,
    )

    fun acceptButtonId(sessionId: Long, opponentDiscordId: Long): String =
        encode(Action.ACCEPT, sessionId, opponentDiscordId)

    fun declineButtonId(sessionId: Long, opponentDiscordId: Long): String =
        encode(Action.DECLINE, sessionId, opponentDiscordId)

    fun placeButtonId(sessionId: Long, cell: Int): String =
        encode(placeAction(cell), sessionId, cell.toLong())

    fun forfeitButtonId(sessionId: Long): String = encode(Action.FORFEIT, sessionId, 0L)

    private fun encode(action: Action, sessionId: Long, payload: Long): String =
        PvpButtonIdCodec.encode(BUTTON_NAME, action.name, sessionId, payload)

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val raw = PvpButtonIdCodec.parse(componentId, BUTTON_NAME) ?: return null
        val action = runCatching { Action.valueOf(raw.actionName) }.getOrNull() ?: return null
        return ParsedButtonId(action, raw.sessionId, raw.payload)
    }

    /** Maps PLACE_<n> → cell index `n`; returns null for non-PLACE actions. */
    fun cellFor(action: Action): Int? = when (action) {
        Action.PLACE_0 -> 0
        Action.PLACE_1 -> 1
        Action.PLACE_2 -> 2
        Action.PLACE_3 -> 3
        Action.PLACE_4 -> 4
        Action.PLACE_5 -> 5
        Action.PLACE_6 -> 6
        Action.PLACE_7 -> 7
        Action.PLACE_8 -> 8
        else -> null
    }

    private fun placeAction(cell: Int): Action = when (cell) {
        0 -> Action.PLACE_0
        1 -> Action.PLACE_1
        2 -> Action.PLACE_2
        3 -> Action.PLACE_3
        4 -> Action.PLACE_4
        5 -> Action.PLACE_5
        6 -> Action.PLACE_6
        7 -> Action.PLACE_7
        8 -> Action.PLACE_8
        else -> error("cell $cell out of range")
    }

    // ---- TTT-specific embeds ----

    fun pendingEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("❌ ⭕  Tic-Tac-Toe")
        .setDescription(
            "<@${opponentDiscordId}> — <@${initiatorDiscordId}> has challenged you to Tic-Tac-Toe." +
                PvpEmbeds.stakeLine(stake) +
                "\nAccept to start, or decline to walk away." +
                "\n\nWinner gets ❌ (moves first). Loser plays ⭕."
        )
        .setColor(0x5B8DEF)
        .build()

    fun turnEmbed(session: TicTacToeSessionRegistry.Session): MessageEmbed {
        val title = "❌ ⭕  Tic-Tac-Toe — <@${session.currentActorDiscordId()}>'s turn"
        val board = renderBoard(session.board, winningLine = null)
        return EmbedBuilder()
            .setTitle(title)
            .setDescription(
                "<@${session.initiatorDiscordId}> (❌) vs <@${session.opponentDiscordId}> (⭕)" +
                    PvpEmbeds.stakeLine(session.stake) +
                    "\n\n" + board
            )
            .setColor(0x5B8DEF)
            .build()
    }

    fun winEmbed(
        session: TicTacToeSessionRegistry.Session,
        outcome: TurnBasedBoardWagerService.ResolveOutcome.Win,
        forfeit: Boolean,
    ): MessageEmbed {
        val winnerMark = session.markFor(outcome.winnerDiscordId)
        val verb = if (forfeit) "wins by forfeit" else "wins"
        val markLabel = winnerMark?.let { " (${prettyMark(it)})" } ?: ""
        val board = renderBoard(session.board, winningLine = session.winningLine)
        return EmbedBuilder()
            .setTitle("🏆 Tic-Tac-Toe — <@${outcome.winnerDiscordId}>$markLabel $verb!")
            .setDescription(
                board +
                    if (outcome.pot > 0) {
                        "\n\nPot: **${outcome.pot}** credits → " +
                            "winner takes **${outcome.pot - outcome.lossTribute}** " +
                            "(jackpot tribute: ${outcome.lossTribute})."
                    } else "" +
                        if (outcome.xpGranted > 0) "\n+${outcome.xpGranted} XP for the winner." else ""
            )
            .setColor(0x57F287)
            .build()
    }

    fun drawEmbed(
        session: TicTacToeSessionRegistry.Session,
        outcome: TurnBasedBoardWagerService.ResolveOutcome.Draw,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("🤝 Tic-Tac-Toe — draw!")
        .setDescription(
            renderBoard(session.board, winningLine = null) +
                "\n\nNo three in a row." +
                if (outcome.stake > 0) " Stakes refunded to both players." else ""
        )
        .setColor(0xFEE75C)
        .build()

    // ---- shared-embed pass-throughs (game-name pre-filled) ----

    fun pendingDeclineEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed =
        PvpEmbeds.pendingDeclineEmbed(GAME_NAME, initiatorDiscordId, opponentDiscordId)

    fun pendingTimeoutEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed =
        PvpEmbeds.pendingTimeoutEmbed(GAME_NAME, initiatorDiscordId, opponentDiscordId)

    // ---- button rows ----

    fun pendingButtons(sessionId: Long, opponentDiscordId: Long): ActionRow =
        PvpEmbeds.pendingButtons(
            acceptButtonId = acceptButtonId(sessionId, opponentDiscordId),
            declineButtonId = declineButtonId(sessionId, opponentDiscordId),
        )

    /**
     * Cell-grid + forfeit. The 3×3 grid is laid out as three rows of
     * three buttons. Each cell button is disabled when occupied so the
     * client surfaces the rule without round-tripping through the bot.
     */
    fun liveButtons(session: TicTacToeSessionRegistry.Session): List<ActionRow> {
        val rows = (0 until 3).map { row ->
            ActionRow.of(
                (0 until 3).map { col ->
                    val cell = row * 3 + col
                    cellButton(session.id, cell, session.board[cell])
                }
            )
        }
        val forfeitRow = ActionRow.of(
            Button.of(ButtonStyle.SECONDARY, forfeitButtonId(session.id), "Forfeit"),
        )
        return rows + forfeitRow
    }

    private fun cellButton(sessionId: Long, cell: Int, mark: TicTacToeEngine.Mark?): Button {
        val id = placeButtonId(sessionId, cell)
        return when (mark) {
            null -> Button.of(ButtonStyle.SECONDARY, id, EMPTY_EMOJI)
            TicTacToeEngine.Mark.X -> Button.of(ButtonStyle.DANGER, id, X_EMOJI).asDisabled()
            TicTacToeEngine.Mark.O -> Button.of(ButtonStyle.PRIMARY, id, O_EMOJI).asDisabled()
        }
    }

    // ---- helpers ----

    /**
     * Render the 9-cell board as a 3×3 emoji grid. Cells in
     * [winningLine] are highlighted with team-coloured squares so the
     * winning row is visually obvious in the final embed.
     */
    private fun renderBoard(
        board: TicTacToeEngine.Board,
        winningLine: List<Int>?,
    ): String {
        val highlight = winningLine?.toSet() ?: emptySet()
        return buildString {
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    val cell = row * 3 + col
                    append(cellEmoji(board[cell], cell in highlight))
                }
                append('\n')
            }
        }.trimEnd()
    }

    private fun cellEmoji(mark: TicTacToeEngine.Mark?, winning: Boolean): String = when {
        mark == TicTacToeEngine.Mark.X && winning -> WIN_X_EMOJI
        mark == TicTacToeEngine.Mark.O && winning -> WIN_O_EMOJI
        mark == TicTacToeEngine.Mark.X -> X_EMOJI
        mark == TicTacToeEngine.Mark.O -> O_EMOJI
        else -> EMPTY_EMOJI
    }

    fun prettyMark(mark: TicTacToeEngine.Mark): String = when (mark) {
        TicTacToeEngine.Mark.X -> "❌"
        TicTacToeEngine.Mark.O -> "⭕"
    }
}
