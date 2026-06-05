package common.pvp.connect4

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import common.pvp.connect4.Connect4Engine

/**
 * Truth table for [Connect4Engine]. Pure-function unit tests — no
 * mocks. Pins gravity, the 4 winning directions, column-full /
 * invalid-column rejection, draw detection, and the "engine is
 * stateless about turn order" property.
 */
class Connect4EngineTest {

    private val R = Connect4Engine.Mark.RED
    private val Y = Connect4Engine.Mark.YELLOW

    @Test
    fun `empty board has no marks and is not full`() {
        val b = Connect4Engine.empty()
        assertTrue(b.cells.all { it == null })
        assertEquals(false, b.isFull)
    }

    @Test
    fun `applyMove rejects out-of-range columns`() {
        val b = Connect4Engine.empty()
        assertEquals(Connect4Engine.MoveResult.InvalidColumn, Connect4Engine.applyMove(b, -1, R))
        assertEquals(Connect4Engine.MoveResult.InvalidColumn, Connect4Engine.applyMove(b, 7, R))
    }

    @Test
    fun `gravity lands the first disc at the bottom row`() {
        val r = Connect4Engine.applyMove(Connect4Engine.empty(), 3, R)
        val cont = r as Connect4Engine.MoveResult.Continued
        assertEquals(5, cont.droppedRow) // bottom of a 6-row board
        assertEquals(R, cont.board[5, 3])
    }

    @Test
    fun `gravity stacks discs above each other in the same column`() {
        var board = Connect4Engine.empty()
        for (i in 0 until Connect4Engine.ROWS) {
            val r = Connect4Engine.applyMove(board, 0, R)
            when (r) {
                is Connect4Engine.MoveResult.Continued -> board = r.board
                is Connect4Engine.MoveResult.Win -> board = r.board
                else -> {} // shouldn't happen
            }
        }
        // All 6 rows of column 0 are RED.
        for (row in 0 until Connect4Engine.ROWS) {
            assertEquals(R, board[row, 0], "row $row col 0 should be RED")
        }
    }

    @Test
    fun `applyMove rejects a column once full`() {
        var board = Connect4Engine.empty()
        repeat(Connect4Engine.ROWS) {
            board = when (val r = Connect4Engine.applyMove(board, 0, R)) {
                is Connect4Engine.MoveResult.Continued -> r.board
                is Connect4Engine.MoveResult.Win -> r.board
                else -> board
            }
        }
        assertEquals(Connect4Engine.MoveResult.ColumnFull, Connect4Engine.applyMove(board, 0, Y))
    }

    @Test
    fun `horizontal 4 in a row wins`() {
        // RED drops cols 0..3 along the bottom.
        var board = Connect4Engine.empty()
        for (col in 0..2) {
            val r = Connect4Engine.applyMove(board, col, R) as Connect4Engine.MoveResult.Continued
            board = r.board
        }
        val win = Connect4Engine.applyMove(board, 3, R) as Connect4Engine.MoveResult.Win
        assertEquals(R, win.winner)
        // Bottom row cells 0..3 = indices 35..38.
        assertEquals(listOf(35, 36, 37, 38), win.winningLine)
        assertEquals(5, win.droppedRow)
    }

    @Test
    fun `vertical 4 in a row wins`() {
        var board = Connect4Engine.empty()
        for (i in 0..2) {
            val r = Connect4Engine.applyMove(board, 4, Y) as Connect4Engine.MoveResult.Continued
            board = r.board
        }
        val win = Connect4Engine.applyMove(board, 4, Y) as Connect4Engine.MoveResult.Win
        assertEquals(Y, win.winner)
        // Column 4, rows 2..5 = indices 18, 25, 32, 39 (top→bottom) or 39, 32, 25, 18 (bottom→top).
        // Engine walks from the start back along (-dRow, -dCol) = (-1, 0), so start = topmost cell.
        // Then walks forward (+1, 0) collecting cells. Result: [(2,4)=18, (3,4)=25, (4,4)=32, (5,4)=39].
        assertEquals(listOf(18, 25, 32, 39), win.winningLine)
    }

    @Test
    fun `diagonal down-right 4 in a row wins`() {
        // Stack a staircase: place RED discs at (5,0), (4,1), (3,2), (2,3).
        // Each needs supporting yellows beneath in cols 1, 2, 3.
        // Build the supporting structure manually via the engine.
        var board = Connect4Engine.empty()
        fun drop(col: Int, mark: Connect4Engine.Mark) {
            board = when (val r = Connect4Engine.applyMove(board, col, mark)) {
                is Connect4Engine.MoveResult.Continued -> r.board
                is Connect4Engine.MoveResult.Win -> r.board
                else -> board
            }
        }
        // bottom-left starting position
        drop(0, R) // (5, 0) = R
        drop(1, Y) // (5, 1) = Y (filler)
        drop(1, R) // (4, 1) = R
        drop(2, Y); drop(2, Y) // (5,2)(4,2)=Y
        drop(2, R) // (3, 2) = R
        drop(3, Y); drop(3, Y); drop(3, Y) // (5,3)(4,3)(3,3)=Y
        val win = Connect4Engine.applyMove(board, 3, R) as Connect4Engine.MoveResult.Win
        assertEquals(R, win.winner)
        // Cells (5,0)=35, (4,1)=29, (3,2)=23, (2,3)=17 — descending row, ascending col
        // Engine walks back along (-1, -1) until off-board, then forward. The first
        // cell in the walk is (2, 3) (top), then (3, 2), (4, 1), (5, 0).
        assertEquals(listOf(17, 23, 29, 35), win.winningLine)
    }

    @Test
    fun `diagonal up-right 4 in a row wins`() {
        // Stack the mirror staircase: place RED at (2,0), (3,1), (4,2), (5,3).
        var board = Connect4Engine.empty()
        fun drop(col: Int, mark: Connect4Engine.Mark) {
            board = when (val r = Connect4Engine.applyMove(board, col, mark)) {
                is Connect4Engine.MoveResult.Continued -> r.board
                is Connect4Engine.MoveResult.Win -> r.board
                else -> board
            }
        }
        drop(0, Y); drop(0, Y); drop(0, Y) // (5,0)(4,0)(3,0)=Y
        drop(0, R) // (2, 0) = R
        drop(1, Y); drop(1, Y) // (5,1)(4,1)=Y
        drop(1, R) // (3, 1) = R
        drop(2, Y) // (5, 2) = Y
        drop(2, R) // (4, 2) = R
        val win = Connect4Engine.applyMove(board, 3, R) as Connect4Engine.MoveResult.Win
        assertEquals(R, win.winner)
        // (5,3)=38, (4,2)=30, (3,1)=22, (2,0)=14 — ascending row, descending col when
        // walking back along (-1, +1). The engine's `dRow=1, dCol=-1` direction walks
        // from start back along (-1, +1) so start = topmost-rightmost cell.
        assertEquals(listOf(14, 22, 30, 38), win.winningLine)
    }

    @Test
    fun `draw fires when board fills with no line`() {
        // Construct a known full-board state with no 4-in-a-row via direct
        // Board injection (bypassing applyMove's gravity for setup). The
        // pattern is "two rows of one alternation then two rows of the
        // mirror alternation, repeating" — pinned diagonals break at the
        // every-2-row colour flip, and every column / row alternates so
        // max consecutive same-mark is 2 in every direction.
        val r = Connect4Engine.Mark.RED
        val y = Connect4Engine.Mark.YELLOW
        val cells = listOf(
            r, y, r, y, r, y, r,
            r, y, r, y, r, y, r,
            y, r, y, r, y, r, y,
            y, r, y, r, y, r, y,
            r, y, r, y, r, y, r,
            // bottom row leaves col 6 empty so we drop YELLOW into it to fill.
            y, r, y, r, y, r, null,
        )
        val board = Connect4Engine.Board(cells)
        val result = Connect4Engine.applyMove(board, 6, y) // drops at (5, 6) = index 41
        assertTrue(result is Connect4Engine.MoveResult.Draw, "expected Draw, got $result")
    }

    @Test
    fun `engine doesn't enforce turn order - caller is responsible`() {
        // Two RED moves in a row are accepted; the engine is intentionally
        // stateless about whose turn it is.
        val first = Connect4Engine.applyMove(Connect4Engine.empty(), 0, R) as Connect4Engine.MoveResult.Continued
        val second = Connect4Engine.applyMove(first.board, 1, R)
        assertTrue(second is Connect4Engine.MoveResult.Continued)
    }

    @Test
    fun `winningLineThrough returns null when no line exists`() {
        val board = Connect4Engine.empty()
        assertNull(Connect4Engine.winningLineThrough(board, 5, 0, R))
    }

    @Test
    fun `Board constructor rejects wrong-size lists`() {
        try {
            Connect4Engine.Board(List(41) { null })
            assert(false) { "expected IllegalArgumentException" }
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `Win result carries the moved board state`() {
        // Confirm Win.board reflects the placed disc.
        var board = Connect4Engine.empty()
        for (col in 0..2) {
            val r = Connect4Engine.applyMove(board, col, R) as Connect4Engine.MoveResult.Continued
            board = r.board
        }
        val win = Connect4Engine.applyMove(board, 3, R) as Connect4Engine.MoveResult.Win
        assertEquals(R, win.board[5, 3])
        assertNotNull(win.board[5, 0])
    }
}
