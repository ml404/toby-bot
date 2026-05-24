package bot.toby.command.commands.game.pvp.connect4

import common.pvp.connect4.Connect4Engine
import database.boardgame.TurnBasedBoardWagerService
import database.connect4.Connect4SessionRegistry
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.MessageEmbed
import bot.toby.command.commands.game.pvp.PvpButtonIdCodec
import bot.toby.command.commands.game.pvp.PvpEmbeds

/**
 * Embed + button rendering specific to `/connect4`. The cross-game
 * bits (Accept/Decline button row, stake-line text, error / decline /
 * timeout embeds, start-error description) live in [PvpEmbeds] —
 * this file only owns the C4-specific board rendering, column-drop
 * buttons, and turn / win / draw embeds.
 *
 * Component IDs are colon-delimited so [DefaultButtonManager] can
 * route by the `"connect4"` prefix and this object can parse the
 * rest by index:
 *
 *   `connect4:<action>:<sessionId>:<payload>`
 *
 * - `action`: ACCEPT / DECLINE during PENDING; DROP_<0..6> /
 *   FORFEIT during LIVE.
 * - `payload`: the opponent's discord id for ACCEPT/DECLINE; the
 *   column index for DROP_*; 0 for FORFEIT.
 */
object Connect4Embeds {

    const val BUTTON_NAME = "connect4"
    private const val GAME_NAME = "Connect 4"

    private const val RED_EMOJI = "🔴"
    private const val YELLOW_EMOJI = "🟡"
    private const val EMPTY_EMOJI = "⚪"
    private const val WIN_RED_EMOJI = "🟥"
    private const val WIN_YELLOW_EMOJI = "🟨"

    enum class Action {
        ACCEPT,
        DECLINE,
        DROP_0, DROP_1, DROP_2, DROP_3, DROP_4, DROP_5, DROP_6,
        FORFEIT,
    }

    data class ParsedButtonId(
        val action: Action,
        val sessionId: Long,
        /** Discord id for ACCEPT/DECLINE; column index for DROP_*; 0 for FORFEIT. */
        val payload: Long,
    )

    fun acceptButtonId(sessionId: Long, opponentDiscordId: Long): String =
        encode(Action.ACCEPT, sessionId, opponentDiscordId)

    fun declineButtonId(sessionId: Long, opponentDiscordId: Long): String =
        encode(Action.DECLINE, sessionId, opponentDiscordId)

    fun dropButtonId(sessionId: Long, column: Int): String =
        encode(dropAction(column), sessionId, column.toLong())

    fun forfeitButtonId(sessionId: Long): String = encode(Action.FORFEIT, sessionId, 0L)

    private fun encode(action: Action, sessionId: Long, payload: Long): String =
        PvpButtonIdCodec.encode(BUTTON_NAME, action.name, sessionId, payload)

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val raw = PvpButtonIdCodec.parse(componentId, BUTTON_NAME) ?: return null
        val action = runCatching { Action.valueOf(raw.actionName) }.getOrNull() ?: return null
        return ParsedButtonId(action, raw.sessionId, raw.payload)
    }

    /** Maps DROP_<n> → column index `n`; returns null for non-DROP actions. */
    fun columnFor(action: Action): Int? = when (action) {
        Action.DROP_0 -> 0
        Action.DROP_1 -> 1
        Action.DROP_2 -> 2
        Action.DROP_3 -> 3
        Action.DROP_4 -> 4
        Action.DROP_5 -> 5
        Action.DROP_6 -> 6
        else -> null
    }

    private fun dropAction(column: Int): Action = when (column) {
        0 -> Action.DROP_0
        1 -> Action.DROP_1
        2 -> Action.DROP_2
        3 -> Action.DROP_3
        4 -> Action.DROP_4
        5 -> Action.DROP_5
        6 -> Action.DROP_6
        else -> error("column $column out of range")
    }

    // ---- C4-specific embeds ----

    fun pendingEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("🔴 🟡  Connect 4")
        .setDescription(
            "<@${opponentDiscordId}> — <@${initiatorDiscordId}> has challenged you to Connect 4." +
                PvpEmbeds.stakeLine(stake) +
                "\nAccept to start, or decline to walk away." +
                "\n\nChallenger plays 🔴 (drops first). Opponent plays 🟡."
        )
        .setColor(0x5B8DEF)
        .build()

    fun turnEmbed(session: Connect4SessionRegistry.Session): MessageEmbed {
        val title = "🔴 🟡  Connect 4 — <@${session.currentActorDiscordId()}>'s turn"
        val board = renderBoard(session.board, winningLine = null)
        return EmbedBuilder()
            .setTitle(title)
            .setDescription(
                "<@${session.initiatorDiscordId}> (🔴) vs <@${session.opponentDiscordId}> (🟡)" +
                    PvpEmbeds.stakeLine(session.stake) +
                    "\n\n" + board
            )
            .setColor(0x5B8DEF)
            .build()
    }

    fun winEmbed(
        session: Connect4SessionRegistry.Session,
        outcome: TurnBasedBoardWagerService.ResolveOutcome.Win,
        forfeit: Boolean,
    ): MessageEmbed {
        val winnerMark = session.markFor(outcome.winnerDiscordId)
        val verb = if (forfeit) "wins by forfeit" else "wins"
        val markLabel = winnerMark?.let { " (${prettyMark(it)})" } ?: ""
        val board = renderBoard(session.board, winningLine = session.winningLine)
        return EmbedBuilder()
            .setTitle("🏆 Connect 4 — <@${outcome.winnerDiscordId}>$markLabel $verb!")
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
        session: Connect4SessionRegistry.Session,
        outcome: TurnBasedBoardWagerService.ResolveOutcome.Draw,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("🤝 Connect 4 — draw!")
        .setDescription(
            renderBoard(session.board, winningLine = null) +
                "\n\nBoard full, no four in a row." +
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
     * Column-drop buttons + forfeit. 7 columns + 1 forfeit = 8 buttons.
     * Discord allows max 5 buttons per row, so split 4 + 4: cols 1–4
     * in row 1, cols 5–7 + forfeit in row 2. A column button is
     * disabled once its top row is filled so the client surfaces the
     * "column full" rule without round-tripping.
     */
    fun liveButtons(session: Connect4SessionRegistry.Session): List<ActionRow> {
        val cols = (0 until Connect4Engine.COLS).map { col -> columnButton(session, col) }
        val row1 = ActionRow.of(cols.subList(0, 4))
        val row2 = ActionRow.of(
            cols.subList(4, 7) + Button.of(ButtonStyle.SECONDARY, forfeitButtonId(session.id), "Forfeit"),
        )
        return listOf(row1, row2)
    }

    private fun columnButton(session: Connect4SessionRegistry.Session, col: Int): Button {
        val id = dropButtonId(session.id, col)
        // The top row of a column is row 0; if it's occupied, the column is full.
        val full = session.board[0, col] != null
        // Label with the column number (1-indexed for human readability).
        val label = "${col + 1}"
        val btn = Button.of(ButtonStyle.PRIMARY, id, label)
        return if (full) btn.asDisabled() else btn
    }

    // ---- helpers ----

    /**
     * Render the 7×6 board as a 7-wide emoji grid. Cells in
     * [winningLine] are highlighted with team-coloured squares so the
     * winning four are visually obvious in the final embed.
     */
    private fun renderBoard(
        board: Connect4Engine.Board,
        winningLine: List<Int>?,
    ): String {
        val highlight = winningLine?.toSet() ?: emptySet()
        return buildString {
            for (row in 0 until Connect4Engine.ROWS) {
                for (col in 0 until Connect4Engine.COLS) {
                    val cellIndex = row * Connect4Engine.COLS + col
                    append(cellEmoji(board[row, col], cellIndex in highlight))
                }
                append('\n')
            }
            // Column-number footer so players can map button labels to columns.
            for (col in 0 until Connect4Engine.COLS) append(":${col + 1}️⃣")
        }.trimEnd()
    }

    private fun cellEmoji(mark: Connect4Engine.Mark?, winning: Boolean): String = when {
        mark == Connect4Engine.Mark.RED && winning -> WIN_RED_EMOJI
        mark == Connect4Engine.Mark.YELLOW && winning -> WIN_YELLOW_EMOJI
        mark == Connect4Engine.Mark.RED -> RED_EMOJI
        mark == Connect4Engine.Mark.YELLOW -> YELLOW_EMOJI
        else -> EMPTY_EMOJI
    }

    fun prettyMark(mark: Connect4Engine.Mark): String = when (mark) {
        Connect4Engine.Mark.RED -> "🔴"
        Connect4Engine.Mark.YELLOW -> "🟡"
    }
}
