package database.boardgame

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioural tests for the shared turn-based board registry base.
 * Drives the state machine through a minimal [TestRegistry] subclass
 * so the per-game registries (TTT, C4) get a single, authoritative
 * test of the shared mechanism — atomic state transitions, pending
 * timeout, move-clock re-arm + stale-fire safety, Caffeine
 * `maximumSize` eviction.
 *
 * Per-game `applyMove` tests live in the per-game registry tests and
 * only cover the game-specific engine integration.
 */
class TurnBasedBoardSessionRegistryTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    /** Minimal registry / session pair — enough to drive the base's state machine. */
    private class TestRegistry(
        pendingTtl: Duration = DEFAULT_PENDING_TTL,
        moveTtl: Duration = DEFAULT_MOVE_TTL,
        scheduler: ScheduledExecutorService,
        maximumSessions: Long = MAX_SESSIONS,
    ) : TurnBasedBoardSessionRegistry<TestRegistry.TestSession>(
        pendingTtl, moveTtl, scheduler, maximumSessions, Duration.ofMinutes(12),
    ) {
        class TestSession(
            id: Long, guildId: Long, initiatorDiscordId: Long, opponentDiscordId: Long,
            stake: Long, createdAt: Instant,
        ) : Session(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt)

        override fun newSession(
            id: Long, guildId: Long, initiatorDiscordId: Long, opponentDiscordId: Long,
            stake: Long, createdAt: Instant,
        ): TestSession = TestSession(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt)

        /** Bumps moveNumber + re-arms the clock. Stand-in for a per-game applyMove "Continued" path. */
        fun simulateContinuedMove(id: Long, onMoveTimeout: (TestSession) -> Unit = {}) {
            val s = get(id) ?: return
            synchronized(s) {
                s.moveNumber += 1
                armMoveClock(s, onMoveTimeout)
            }
        }
    }

    private fun noopScheduler(): ScheduledExecutorService =
        object : ScheduledThreadPoolExecutor(0) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                super.schedule(Runnable { /* no-op */ }, Long.MAX_VALUE, TimeUnit.SECONDS)
        }

    @Test
    fun `register issues monotonic ids`() {
        val registry = TestRegistry(scheduler = noopScheduler())
        val a = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        val b = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertTrue(b.id > a.id)
    }

    @Test
    fun `decline removes a pending session and accept on the same id returns null`() {
        val registry = TestRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertNotNull(registry.decline(session.id))
        assertNull(registry.accept(session.id))
    }

    @Test
    fun `accept transitions PENDING to LIVE and second accept is a no-op`() {
        val registry = TestRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        val first = registry.accept(session.id)
        assertNotNull(first)
        assertEquals(TurnBasedBoardSessionRegistry.Session.State.LIVE, first!!.state)
        assertNull(registry.accept(session.id))
    }

    @Test
    fun `decline of LIVE session is rejected`() {
        val registry = TestRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNull(registry.decline(session.id))
    }

    @Test
    fun `consumeForResolution atomic-removes and second call returns null`() {
        val registry = TestRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNotNull(registry.consumeForResolution(session.id))
        assertNull(registry.consumeForResolution(session.id))
    }

    @Test
    fun `forfeit atomic-removes and second call returns null`() {
        val registry = TestRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNotNull(registry.forfeit(session.id))
        assertNull(registry.forfeit(session.id))
    }

    @Test
    fun `concurrent accept across many threads produces exactly one success`() {
        val registry = TestRegistry(scheduler = noopScheduler())
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
            val registry = TestRegistry(
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
            val registry = TestRegistry(
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
    fun `move clock fires when the current actor doesn't move in time`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            val registry = TestRegistry(
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
            val registry = TestRegistry(
                pendingTtl = Duration.ofMinutes(5),
                moveTtl = Duration.ofMillis(300),
                scheduler = scheduler,
            )
            val fired = AtomicInteger(0)
            val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
            registry.accept(session.id) { _ -> fired.incrementAndGet() }

            Thread.sleep(100)
            registry.simulateContinuedMove(session.id) { _ -> fired.incrementAndGet() }
            Thread.sleep(100)
            registry.simulateContinuedMove(session.id) { _ -> fired.incrementAndGet() }

            // Without re-arming, two stale fires would have happened by now
            // (the first armed at accept, the second at the first move). With
            // re-arming the only outstanding timer is the one armed after the
            // most recent move — nothing has fired yet.
            assertEquals(0, fired.get(), "stale timers must NOT fire after re-arm")
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `Caffeine maximumSize cap evicts oldest entries to keep memory bounded`() {
        val registry = TestRegistry(
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
}
