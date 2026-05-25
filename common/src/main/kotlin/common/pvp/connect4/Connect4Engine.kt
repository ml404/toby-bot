package common.pvp.connect4

/**
 * Pure logic for 7×6 Connect 4 outcomes. No JDA / Spring / DB —
 * tested as plain functions so the game rules can't drift.
 *
 * Cells are indexed row-major. Row 0 is the **top** of the board,
 * row 5 is the **bottom**. A move drops a disc into a column; the
 * disc lands in the lowest empty row of that column (gravity), so
 * the first move into column `c` lands at `(row=5, col=c)`.
 *
 * Marks (`RED` / `YELLOW`) are abstract — the calling service maps
 * each Discord id onto a Mark so the engine never has to know about
 * Discord. By convention the initiator plays RED and moves first.
 */
object Connect4Engine {

    enum class Mark { RED, YELLOW }

    /**
     * Immutable 7×6 board. `cells[row * COLS + col]` is the mark at
     * that position, or `null` if empty. Use [empty] to build a fresh
     * game; never construct from arbitrary input the engine didn't
     * produce.
     */
    data class Board(val cells: List<Mark?> = List(CELL_COUNT) { null }) {
        init { require(cells.size == CELL_COUNT) { "Board must have exactly $CELL_COUNT cells" } }
        val isFull: Boolean get() = cells.all { it != null }
        operator fun get(row: Int, col: Int): Mark? = cells[row * COLS + col]
    }

    /** Outcome of a single [applyMove] call. */
    sealed interface MoveResult {
        /** `column` was outside the valid 0..6 range. */
        data object InvalidColumn : MoveResult

        /** `column` has no empty row left. */
        data object ColumnFull : MoveResult

        /**
         * Move accepted, game continues — next player to act.
         * [droppedRow] is the row the disc actually landed in (so the
         * renderer can highlight the newly-placed cell).
         */
        data class Continued(val board: Board, val droppedRow: Int) : MoveResult

        /**
         * Move accepted and completed a four-in-a-row.
         * [winningLine] is the four cell indices (row * COLS + col)
         * that line up so the renderer can highlight them.
         * [droppedRow] mirrors [Continued.droppedRow].
         */
        data class Win(
            val board: Board,
            val winner: Mark,
            val winningLine: List<Int>,
            val droppedRow: Int,
        ) : MoveResult

        /** Move accepted, board is full, no winner. */
        data class Draw(val board: Board) : MoveResult
    }

    /** A fresh empty board. */
    fun empty(): Board = Board()

    /**
     * Drop a [mark] into [column] on [board]. Returns one of:
     *  - [MoveResult.InvalidColumn] / [MoveResult.ColumnFull] when
     *    the move is rejected — board is unchanged
     *  - [MoveResult.Continued] when the disc lands and the game
     *    continues
     *  - [MoveResult.Win] when the placement completes a 4-in-a-row
     *  - [MoveResult.Draw] when the placement fills the last empty
     *    cell without lining up
     *
     * The engine does NOT track whose turn it is — the caller is
     * responsible for alternating marks. Stateless engine, easy
     * tests.
     */
    fun applyMove(board: Board, column: Int, mark: Mark): MoveResult {
        if (column !in 0 until COLS) return MoveResult.InvalidColumn
        // Gravity: find the lowest empty row in this column.
        val droppedRow = lowestEmptyRow(board, column) ?: return MoveResult.ColumnFull
        val updatedCells = board.cells.toMutableList().also { it[droppedRow * COLS + column] = mark }
        val next = Board(updatedCells)
        winningLineThrough(next, droppedRow, column, mark)?.let {
            return MoveResult.Win(next, mark, it, droppedRow)
        }
        return if (next.isFull) MoveResult.Draw(next) else MoveResult.Continued(next, droppedRow)
    }

    /**
     * Returns the four cell indices forming a 4-in-a-row that passes
     * through (`row`, `col`) for [mark], or `null` if none exists.
     * Checks the four directions a fresh placement can extend:
     * horizontal, vertical, diagonal-down (`\`), diagonal-up (`/`).
     */
    fun winningLineThrough(board: Board, row: Int, col: Int, mark: Mark): List<Int>? {
        for ((dRow, dCol) in DIRECTIONS) {
            val line = lineThrough(board, row, col, dRow, dCol, mark)
            if (line != null) return line
        }
        return null
    }

    private fun lineThrough(
        board: Board, row: Int, col: Int, dRow: Int, dCol: Int, mark: Mark,
    ): List<Int>? {
        // Walk back along the (-dRow, -dCol) direction to the line's start
        // (or the board edge) while marks match. Then walk forward along
        // (+dRow, +dCol) counting consecutive same-marks. Window of 4 wins.
        var startRow = row; var startCol = col
        while (true) {
            val nr = startRow - dRow; val nc = startCol - dCol
            if (!inBounds(nr, nc) || board[nr, nc] != mark) break
            startRow = nr; startCol = nc
        }
        val cells = mutableListOf<Int>()
        var r = startRow; var c = startCol
        while (inBounds(r, c) && board[r, c] == mark) {
            cells.add(r * COLS + c)
            r += dRow; c += dCol
        }
        // Return the first 4-window that contains (row, col) — there can be
        // longer runs (e.g. 5 in a row) and we want to highlight any 4 that
        // includes the just-placed cell.
        val placedIndex = row * COLS + col
        for (i in 0..cells.size - 4) {
            val window = cells.subList(i, i + 4)
            if (placedIndex in window) return window.toList()
        }
        return null
    }

    private fun lowestEmptyRow(board: Board, column: Int): Int? {
        for (row in ROWS - 1 downTo 0) {
            if (board[row, column] == null) return row
        }
        return null
    }

    private fun inBounds(row: Int, col: Int): Boolean =
        row in 0 until ROWS && col in 0 until COLS

    const val COLS: Int = 7
    const val ROWS: Int = 6
    const val CELL_COUNT: Int = COLS * ROWS

    /** The 4 line directions a placement can extend: horizontal, vertical, two diagonals. */
    private val DIRECTIONS: List<Pair<Int, Int>> = listOf(
        0 to 1,   // horizontal →
        1 to 0,   // vertical ↓
        1 to 1,   // diagonal-down ↘
        1 to -1,  // diagonal-up (drawn-down-and-left) ↙
    )
}
