package database.tictactoe

import common.tictactoe.TicTacToeEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioural tests for [TicTacToeSessionRegistry] — pin race-safety
 * on the atomic state transitions and the per-move shot-clock
 * re-arming.
 */
class TicTacToeSessionRegistryTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    @Test
    fun `register issues monotonic ids`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val a = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        val b = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertTrue(b.id > a.id)
    }

    @Test
    fun `decline removes a pending session and accept on the same id returns null`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertNotNull(registry.decline(session.id))
        assertNull(registry.accept(session.id))
    }

    @Test
    fun `accept transitions PENDING to LIVE and second accept is a no-op`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        val first = registry.accept(session.id)
        assertNotNull(first)
        assertEquals(TicTacToeSessionRegistry.Session.State.LIVE, first!!.state)
        assertNull(registry.accept(session.id))
    }

    @Test
    fun `decline of LIVE session is rejected`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNull(registry.decline(session.id))
    }

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
        // No accept yet — session is still PENDING.
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
    fun `consumeForResolution atomic-removes and second call returns null`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNotNull(registry.consumeForResolution(session.id))
        assertNull(registry.consumeForResolution(session.id))
    }

    @Test
    fun `forfeit atomic-removes and second call returns null`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNotNull(registry.forfeit(session.id))
        assertNull(registry.forfeit(session.id))
    }

    @Test
    fun `concurrent accept across many threads produces exactly one success`() {
        val registry = TicTacToeSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        repeat(threads) {
            pool.submit {
                start.await()
                if (registry.accept(session.id) != null) successes.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(1, successes.get())
    }

    @Test
    fun `pending timeout fires its callback exactly once when nothing else consumes the session`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            val registry = TicTacToeSessionRegistry(
                pendingTtl = Duration.ofMillis(100),
                moveTtl = Duration.ofMinutes(5),
                scheduler = scheduler,
            )
            val fired = AtomicInteger(0)
            registry.register(guildId, initiatorId, opponentId, stake = 10L) { _ ->
                fired.incrementAndGet()
            }
            Thread.sleep(1_000)
            assertEquals(1, fired.get())
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `pending timeout is a no-op when the session has already accepted`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            val registry = TicTacToeSessionRegistry(
                pendingTtl = Duration.ofMillis(100),
                moveTtl = Duration.ofMinutes(5),
                scheduler = scheduler,
            )
            val fired = AtomicInteger(0)
            val session = registry.register(guildId, initiatorId, opponentId, stake = 10L) { _ ->
                fired.incrementAndGet()
            }
            registry.accept(session.id)
            Thread.sleep(1_000)
            assertEquals(0, fired.get())
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `move clock fires when the current actor doesn't place a mark in time`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            val registry = TicTacToeSessionRegistry(
                pendingTtl = Duration.ofMinutes(5),
                moveTtl = Duration.ofMillis(150),
                scheduler = scheduler,
            )
            val fired = AtomicInteger(0)
            val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
            registry.accept(session.id) { _ -> fired.incrementAndGet() }
            Thread.sleep(1_000)
            assertEquals(1, fired.get(), "move timeout must fire once no one moved within moveTtl")
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `move clock is re-armed on each successful move so a fresh actor gets the full window`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            val registry = TicTacToeSessionRegistry(
                pendingTtl = Duration.ofMinutes(5),
                // Long enough that without re-arming we'd time out
                // halfway through the test, but short enough that the
                // test doesn't drag.
                moveTtl = Duration.ofMillis(300),
                scheduler = scheduler,
            )
            val fired = AtomicInteger(0)
            val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
            registry.accept(session.id) { _ -> fired.incrementAndGet() }

            // Initiator moves within the window → clock re-arms for opponent.
            Thread.sleep(100)
            assertNotNull(registry.applyMove(session.id, initiatorId, 0))
            // Opponent moves within the (re-armed) window → clock re-arms for initiator.
            Thread.sleep(100)
            assertNotNull(registry.applyMove(session.id, opponentId, 4))

            // Without re-arming, two stale fires would have happened by
            // now (totalling 200ms past the original 300ms window). With
            // re-arming, neither stale fire wakes — the only outstanding
            // timer is the one armed after the last move.
            assertEquals(0, fired.get(), "stale timers must NOT fire after re-arm")
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `Caffeine maximumSize cap evicts oldest entries to keep memory bounded`() {
        val registry = TicTacToeSessionRegistry(
            scheduler = noopScheduler(),
            maximumSessions = 3L,
        )
        val ids = (1..10).map {
            registry.register(guildId, initiatorId + it, opponentId + it, stake = 0L).id
        }
        ids.forEach { registry.get(it) }
        Thread.sleep(50)
        val surviving = ids.count { registry.get(it) != null }
        assertTrue(surviving <= 3, "expected at most 3 survivors with maximumSessions=3, got $surviving")
    }

    private fun noopScheduler(): ScheduledExecutorService =
        object : ScheduledThreadPoolExecutor(0) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                super.schedule(Runnable { /* no-op */ }, Long.MAX_VALUE, TimeUnit.SECONDS)
        }
}
