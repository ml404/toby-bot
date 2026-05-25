package common.pvp.tictactoe

/**
 * Pure logic for 3×3 Tic-Tac-Toe outcomes. No JDA / Spring / DB —
 * tested as plain functions so the game rules can't drift.
 *
 * Cells are indexed 0..8 row-major:
 * ```
 *   0 | 1 | 2
 *  ---+---+---
 *   3 | 4 | 5
 *  ---+---+---
 *   6 | 7 | 8
 * ```
 *
 * Marks (`X` / `O`) are abstract — the calling service maps each
 * Discord id onto a Mark so the engine never has to know about Discord.
 */
object TicTacToeEngine {

    enum class Mark { X, O }

    /**
     * Immutable 9-cell board. `cells[i]` is the mark at cell `i`, or
     * `null` if empty. Use [empty] to build a fresh game; never
     * construct from arbitrary input the engine didn't produce.
     */
    data class Board(val cells: List<Mark?> = List(CELL_COUNT) { null }) {
        init { require(cells.size == CELL_COUNT) { "Board must have exactly $CELL_COUNT cells" } }
        val isFull: Boolean get() = cells.all { it != null }
        operator fun get(cell: Int): Mark? = cells[cell]
    }

    /** Outcome of a single [applyMove] call. */
    sealed interface MoveResult {
        /** `cell` was outside the valid 0..8 range. */
        data object IllegalCell : MoveResult

        /** `cell` was already occupied. */
        data object Occupied : MoveResult

        /** Move accepted, game continues — next player to act. */
        data class Continued(val board: Board) : MoveResult

        /**
         * Move accepted and completed a three-in-a-row. [winningLine] is
         * the three cell indices that line up so the renderer can
         * highlight them.
         */
        data class Win(val board: Board, val winner: Mark, val winningLine: List<Int>) : MoveResult

        /** Move accepted, board is full, no winner. */
        data class Draw(val board: Board) : MoveResult
    }

    /** A fresh empty board. */
    fun empty(): Board = Board()

    /**
     * Place [mark] at [cell] on [board]. Returns one of:
     *   - [MoveResult.IllegalCell] / [MoveResult.Occupied] when the move
     *     is rejected — board is unchanged
     *   - [MoveResult.Continued] when the move lands and the game
     *     continues
     *   - [MoveResult.Win] when the move completes a three-in-a-row
     *   - [MoveResult.Draw] when the move fills the last empty cell
     *     without lining up
     *
     * The engine does NOT track whose turn it is — the caller is
     * responsible for alternating marks. This keeps the engine
     * stateless and trivially testable.
     */
    fun applyMove(board: Board, cell: Int, mark: Mark): MoveResult {
        if (cell !in 0 until CELL_COUNT) return MoveResult.IllegalCell
        if (board[cell] != null) return MoveResult.Occupied
        val next = Board(board.cells.toMutableList().also { it[cell] = mark })
        winningLineFor(next, mark)?.let { return MoveResult.Win(next, mark, it) }
        return if (next.isFull) MoveResult.Draw(next) else MoveResult.Continued(next)
    }

    /**
     * Returns the three-in-a-row line for [mark] if there is one, else
     * `null`. Public for tests / renderers that want to find a winning
     * line without applying a move.
     */
    fun winningLineFor(board: Board, mark: Mark): List<Int>? {
        for (line in LINES) {
            if (board[line[0]] == mark && board[line[1]] == mark && board[line[2]] == mark) return line
        }
        return null
    }

    const val CELL_COUNT: Int = 9

    /** The 8 winning lines: 3 rows, 3 columns, 2 diagonals. */
    val LINES: List<List<Int>> = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
        listOf(0, 4, 8), listOf(2, 4, 6),
    )
}
