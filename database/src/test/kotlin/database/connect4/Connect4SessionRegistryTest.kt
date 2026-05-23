package database.connect4

import common.connect4.Connect4Engine
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
 * Tests for the C4-specific [Connect4SessionRegistry.applyMove]
 * integration with [Connect4Engine]. The shared state-machine /
 * shot-clock / Caffeine plumbing lives in the base class and is
 * covered by [database.boardgame.TurnBasedBoardSessionRegistryTest];
 * this suite only verifies the C4 board / turn / winner mutations
 * (including the gravity-driven landing row).
 */
class Connect4SessionRegistryTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    @Test
    fun `applyMove drops disc with gravity at the bottom row and flips current turn`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // Initiator (RED) drops first.
        val r = registry.applyMove(session.id, initiatorId, column = 3)
        val cont = r as Connect4Engine.MoveResult.Continued
        assertEquals(5, cont.droppedRow)
        val refreshed = registry.get(session.id)!!
        assertEquals(Connect4Engine.Mark.RED, refreshed.board[5, 3])
        assertEquals(Connect4Engine.Mark.YELLOW, refreshed.currentTurn)
        assertEquals(5, refreshed.lastDroppedRow)
        assertEquals(3, refreshed.lastDroppedCol)
    }

    @Test
    fun `applyMove rejects out-of-turn play`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // RED's turn first — opponent (YELLOW) returns null.
        assertNull(registry.applyMove(session.id, opponentId, column = 0))
    }

    @Test
    fun `applyMove rejects non-participant`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNull(registry.applyMove(session.id, discordId = 9999L, column = 0))
    }

    @Test
    fun `applyMove on a PENDING session is rejected`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertNull(registry.applyMove(session.id, initiatorId, column = 0))
    }

    @Test
    fun `winning move returns Win and stamps the session's winner and winningLine`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // Build a horizontal RED win at the bottom row: R drops cols 0..3 with
        // YELLOW fillers at col 4 in between to keep turn order alternating.
        registry.applyMove(session.id, initiatorId, 0) // R at (5,0)
        registry.applyMove(session.id, opponentId, 4) // Y at (5,4)
        registry.applyMove(session.id, initiatorId, 1) // R at (5,1)
        registry.applyMove(session.id, opponentId, 4) // Y at (4,4)
        registry.applyMove(session.id, initiatorId, 2) // R at (5,2)
        registry.applyMove(session.id, opponentId, 4) // Y at (3,4)
        val r = registry.applyMove(session.id, initiatorId, 3) // R at (5,3) → win
        assertTrue(r is Connect4Engine.MoveResult.Win, "expected Win, got $r")
        val live = registry.get(session.id)!!
        assertEquals(Connect4Engine.Mark.RED, live.winner)
        assertEquals(listOf(35, 36, 37, 38), live.winningLine)
    }

    @Test
    fun `applyMove rejects a full column without mutating the board`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // Fill column 0 by alternating drops (6 total to fill all 6 rows).
        for (i in 0 until Connect4Engine.ROWS) {
            val player = if (i % 2 == 0) initiatorId else opponentId
            registry.applyMove(session.id, player, 0)
        }
        // Whoever's turn it is now tries to drop another disc into the full column.
        val live = registry.get(session.id)!!
        val nextPlayer = live.currentActorDiscordId()
        val r = registry.applyMove(session.id, nextPlayer, 0)
        assertEquals(Connect4Engine.MoveResult.ColumnFull, r)
        // Turn / move counter didn't advance.
        val after = registry.get(session.id)!!
        assertEquals(live.moveNumber, after.moveNumber)
        assertEquals(live.currentTurn, after.currentTurn)
    }

    @Test
    fun `Session helpers map between Discord ids and the Mark enum`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertEquals(Connect4Engine.Mark.RED, session.markFor(initiatorId))
        assertEquals(Connect4Engine.Mark.YELLOW, session.markFor(opponentId))
        assertNull(session.markFor(9999L))
        assertEquals(initiatorId, session.currentActorDiscordId())
    }

    @Test
    fun `register returns a Session via the inherited TurnBasedBoardSessionRegistry typing`() {
        val registry = Connect4SessionRegistry(scheduler = noopScheduler())
        val session: Connect4SessionRegistry.Session =
            registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertNotNull(session)
    }

    private fun noopScheduler(): ScheduledExecutorService =
        object : ScheduledThreadPoolExecutor(0) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                super.schedule(Runnable { /* no-op */ }, Long.MAX_VALUE, TimeUnit.SECONDS)
        }
}
