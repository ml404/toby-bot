package bot.toby.command.commands.economy

import common.tictactoe.TicTacToeEngine
import database.service.TicTacToeService
import database.tictactoe.TicTacToeSessionRegistry
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Embed + button rendering for `/tictactoe`.
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
 *   ACCEPT/DECLINE; the cell index for PLACE_*; 0 for FORFEIT
 *   (kept in the format for parser symmetry).
 *
 * The board renders inside the embed description as a 3×3 emoji grid
 * (❌ / ⭕ / ⬜). Underneath sit the cell buttons: each cell is its
 * own button, disabled if already occupied or the game is over. A
 * "Forfeit" button sits on its own row.
 */
object TicTacToeEmbeds {

    const val BUTTON_NAME = "tictactoe"

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
        listOf(BUTTON_NAME, action.name, sessionId.toString(), payload.toString())
            .joinToString(":")

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val parts = componentId.split(':')
        if (parts.size != 4 || !parts[0].equals(BUTTON_NAME, ignoreCase = true)) return null
        val action = runCatching { Action.valueOf(parts[1]) }.getOrNull() ?: return null
        val sessionId = parts[2].toLongOrNull() ?: return null
        val payload = parts[3].toLongOrNull() ?: return null
        return ParsedButtonId(action, sessionId, payload)
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

    // ---- embeds ----

    fun pendingEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("❌ ⭕  Tic-Tac-Toe")
        .setDescription(
            "<@${opponentDiscordId}> — <@${initiatorDiscordId}> has challenged you to Tic-Tac-Toe." +
                stakeLine(stake) +
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
                    stakeLine(session.stake) +
                    "\n\n" + board
            )
            .setColor(0x5B8DEF)
            .build()
    }

    fun winEmbed(
        session: TicTacToeSessionRegistry.Session,
        outcome: TicTacToeService.ResolveOutcome.Win,
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
        outcome: TicTacToeService.ResolveOutcome.Draw,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("🤝 Tic-Tac-Toe — draw!")
        .setDescription(
            renderBoard(session.board, winningLine = null) +
                "\n\nNo three in a row." +
                if (outcome.stake > 0) " Stakes refunded to both players." else ""
        )
        .setColor(0xFEE75C)
        .build()

    fun pendingDeclineEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Challenge declined")
        .setDescription("<@${opponentDiscordId}> declined <@${initiatorDiscordId}>'s Tic-Tac-Toe challenge.")
        .setColor(0xED4245)
        .build()

    fun pendingTimeoutEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed = EmbedBuilder()
        .setTitle("⌛ Challenge expired")
        .setDescription("<@${opponentDiscordId}> didn't respond to <@${initiatorDiscordId}>'s Tic-Tac-Toe challenge.")
        .setColor(0xED4245)
        .build()

    fun startErrorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Can't start that match")
        .setDescription(message)
        .setColor(0xED4245)
        .build()

    fun acceptErrorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Can't accept that match")
        .setDescription(message)
        .setColor(0xED4245)
        .build()

    // ---- button rows ----

    fun pendingButtons(sessionId: Long, opponentDiscordId: Long): ActionRow = ActionRow.of(
        Button.success(acceptButtonId(sessionId, opponentDiscordId), "Accept"),
        Button.danger(declineButtonId(sessionId, opponentDiscordId), "Decline"),
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

    private fun stakeLine(stake: Long): String =
        if (stake > 0L) "\nStake: **${stake}** credits each (winner takes the pot)." else ""

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
