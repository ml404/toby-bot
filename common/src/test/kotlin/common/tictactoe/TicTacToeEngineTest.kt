package common.tictactoe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Truth table for [TicTacToeEngine]. Pure-function unit tests — no
 * mocks. Pins the 8 winning lines, draw detection, illegal moves, and
 * the "engine is stateless about turn order" property.
 */
class TicTacToeEngineTest {

    private val X = TicTacToeEngine.Mark.X
    private val O = TicTacToeEngine.Mark.O

    @Test
    fun `empty board has no marks and is not full`() {
        val b = TicTacToeEngine.empty()
        assertTrue(b.cells.all { it == null })
        assertEquals(false, b.isFull)
    }

    @Test
    fun `applyMove rejects out-of-range cells`() {
        val b = TicTacToeEngine.empty()
        assertEquals(TicTacToeEngine.MoveResult.IllegalCell, TicTacToeEngine.applyMove(b, -1, X))
        assertEquals(TicTacToeEngine.MoveResult.IllegalCell, TicTacToeEngine.applyMove(b, 9, X))
        assertEquals(TicTacToeEngine.MoveResult.IllegalCell, TicTacToeEngine.applyMove(b, 100, X))
    }

    @Test
    fun `applyMove rejects occupied cells regardless of mark`() {
        val first = (TicTacToeEngine.applyMove(TicTacToeEngine.empty(), 4, X)
            as TicTacToeEngine.MoveResult.Continued).board
        assertEquals(TicTacToeEngine.MoveResult.Occupied, TicTacToeEngine.applyMove(first, 4, X))
        assertEquals(TicTacToeEngine.MoveResult.Occupied, TicTacToeEngine.applyMove(first, 4, O))
    }

    @Test
    fun `single move continues with the mark placed`() {
        val r = TicTacToeEngine.applyMove(TicTacToeEngine.empty(), 0, X)
        val cont = r as TicTacToeEngine.MoveResult.Continued
        assertEquals(X, cont.board[0])
        assertEquals(null, cont.board[1])
    }

    @Test
    fun `top row wins for X`() {
        val b = TicTacToeEngine.applyMove(TicTacToeEngine.empty(), 0, X)
            .let { (it as TicTacToeEngine.MoveResult.Continued).board }
        val b2 = TicTacToeEngine.applyMove(b, 1, X)
            .let { (it as TicTacToeEngine.MoveResult.Continued).board }
        val r = TicTacToeEngine.applyMove(b2, 2, X)
        val win = r as TicTacToeEngine.MoveResult.Win
        assertEquals(X, win.winner)
        assertEquals(listOf(0, 1, 2), win.winningLine)
    }

    @Test
    fun `every winning line is detected for both marks`() {
        // 8 lines × 2 marks = 16 cases. Build a board where exactly the
        // given line is filled with the given mark and confirm engine
        // detects it.
        for (line in TicTacToeEngine.LINES) {
            for (mark in TicTacToeEngine.Mark.entries) {
                val cells = MutableList<TicTacToeEngine.Mark?>(9) { null }
                cells[line[0]] = mark
                cells[line[1]] = mark
                val board = TicTacToeEngine.Board(cells)
                val r = TicTacToeEngine.applyMove(board, line[2], mark)
                val win = r as TicTacToeEngine.MoveResult.Win
                assertEquals(mark, win.winner, "expected $mark to win on line $line")
                assertEquals(line, win.winningLine, "expected winning line $line")
            }
        }
    }

    @Test
    fun `draw fires when board fills with no line`() {
        // Sequence yields a full board with no 3-in-a-row:
        //   X O X
        //   X O O
        //   O X X
        val moves = listOf(
            0 to X, 1 to O, 2 to X,
            3 to X, 4 to O, 5 to O,
            7 to X, 8 to X, /* last move below */
        )
        var board = TicTacToeEngine.empty()
        for ((cell, mark) in moves) {
            val r = TicTacToeEngine.applyMove(board, cell, mark)
            board = (r as TicTacToeEngine.MoveResult.Continued).board
        }
        // Final cell — must not complete a line (the only remaining
        // cell is 6 and the only line through 6 that's filled with O is
        // 6,4,2 which would need O at 6, but we play O at 6 so check
        // separately).
        val final = TicTacToeEngine.applyMove(board, 6, O)
        // Cell 6 with O completes diagonal 6,4,2? cells 4=O, 2=X — no.
        // It completes column 6,3,0? 3=X, 0=X — no.
        // It completes row 6,7,8? 7=X, 8=X — no.
        // So it's a draw.
        assertTrue(final is TicTacToeEngine.MoveResult.Draw, "expected Draw, got $final")
    }

    @Test
    fun `engine doesn't enforce turn order — caller is responsible`() {
        // Two X moves in a row are accepted; the engine is intentionally
        // stateless about whose turn it is.
        val first = TicTacToeEngine.applyMove(TicTacToeEngine.empty(), 0, X)
            .let { (it as TicTacToeEngine.MoveResult.Continued).board }
        val second = TicTacToeEngine.applyMove(first, 1, X)
        assertTrue(second is TicTacToeEngine.MoveResult.Continued)
    }

    @Test
    fun `winningLineFor returns null on empty board`() {
        assertNull(TicTacToeEngine.winningLineFor(TicTacToeEngine.empty(), X))
        assertNull(TicTacToeEngine.winningLineFor(TicTacToeEngine.empty(), O))
    }

    @Test
    fun `winningLineFor finds a completed line without applying a move`() {
        val cells = MutableList<TicTacToeEngine.Mark?>(9) { null }
        cells[0] = X; cells[4] = X; cells[8] = X
        val board = TicTacToeEngine.Board(cells)
        assertEquals(listOf(0, 4, 8), TicTacToeEngine.winningLineFor(board, X))
        assertNull(TicTacToeEngine.winningLineFor(board, O))
    }

    @Test
    fun `Board constructor rejects wrong-size lists`() {
        try {
            TicTacToeEngine.Board(List(8) { null })
            assert(false) { "expected IllegalArgumentException" }
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `Win result carries the moved board state`() {
        // Confirm the Win.board reflects the placed mark, not the
        // pre-move board. Otherwise the renderer would highlight a
        // board that doesn't have the winning move on it.
        val cells = MutableList<TicTacToeEngine.Mark?>(9) { null }
        cells[0] = X; cells[1] = X
        val board = TicTacToeEngine.Board(cells)
        val win = TicTacToeEngine.applyMove(board, 2, X) as TicTacToeEngine.MoveResult.Win
        assertEquals(X, win.board[2])
        assertNotNull(win.board[0])
    }
}
