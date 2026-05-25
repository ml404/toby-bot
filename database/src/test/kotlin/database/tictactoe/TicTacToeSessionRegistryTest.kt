package database.tictactoe

import common.pvp.tictactoe.TicTacToeEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Tests for the TTT-specific [TicTacToeSessionRegistry.applyMove]
 * integration with [TicTacToeEngine]. The shared state-machine /
 * shot-clock / Caffeine plumbing lives in the base class and is
 * covered by [database.boardgame.TurnBasedBoardSessionRegistryTest];
 * this suite only verifies the TTT board / turn / winner mutations.
 */
class TicTacToeSessionRegistryTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    @Test
    fun `applyMove places mark and flips current turn`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // Initiator (X) goes first.
        val r = registry.applyMove(session.id, initiatorId, cell = 0)
        assertTrue(r is TicTacToeEngine.MoveResult.Continued)
        val refreshed = registry.get(session.id)!!
        assertEquals(TicTacToeEngine.Mark.X, refreshed.board[0])
        assertEquals(TicTacToeEngine.Mark.O, refreshed.currentTurn)
    }

    @Test
    fun `applyMove rejects out-of-turn play`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // Initiator's turn first — opponent attempting to play returns null.
        assertNull(registry.applyMove(session.id, opponentId, cell = 0))
    }

    @Test
    fun `applyMove rejects non-participant`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNull(registry.applyMove(session.id, discordId = 9999L, cell = 0))
    }

    @Test
    fun `applyMove on a PENDING session is rejected`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertNull(registry.applyMove(session.id, initiatorId, cell = 0))
    }

    @Test
    fun `winning move returns Win and stamps the session's winner and winningLine`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // Play X at 0, O at 3, X at 1, O at 4, X at 2 → X wins top row.
        registry.applyMove(session.id, initiatorId, 0)
        registry.applyMove(session.id, opponentId, 3)
        registry.applyMove(session.id, initiatorId, 1)
        registry.applyMove(session.id, opponentId, 4)
        val r = registry.applyMove(session.id, initiatorId, 2)
        assertTrue(r is TicTacToeEngine.MoveResult.Win)
        val live = registry.get(session.id)!!
        assertEquals(TicTacToeEngine.Mark.X, live.winner)
        assertEquals(listOf(0, 1, 2), live.winningLine)
    }

    @Test
    fun `Session helpers map between Discord ids and the Mark enum`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertEquals(TicTacToeEngine.Mark.X, session.markFor(initiatorId))
        assertEquals(TicTacToeEngine.Mark.O, session.markFor(opponentId))
        assertNull(session.markFor(9999L))
        assertEquals(initiatorId, session.currentActorDiscordId())
    }

    @Test
    fun `register returns a Session via the inherited TurnBasedBoardSessionRegistry typing`() {
        // Ensures the self-typed generic propagates so callers don't need casts.
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session: TicTacToeSessionRegistry.Session =
            registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertNotNull(session)
    }

    private fun noopScheduler(): ScheduledExecutorService =
        object : ScheduledThreadPoolExecutor(0) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                super.schedule(Runnable { /* no-op */ }, Long.MAX_VALUE, TimeUnit.SECONDS)
        }
}
