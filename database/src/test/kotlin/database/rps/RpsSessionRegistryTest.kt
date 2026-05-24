package database.rps

import common.pvp.rps.RpsEngine
import database.pvp.PvpSessionRegistry
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
 * Behavioural tests for [RpsSessionRegistry] — pin race-safety on the
 * atomic state transitions (the entire reason this thing exists rather
 * than a HashMap).
 */
class RpsSessionRegistryTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    @Test
    fun `register issues monotonic ids`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val a = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        val b = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        assertTrue(b.id > a.id, "ids should be monotonically increasing")
    }

    @Test
    fun `decline removes a pending session and accept on the same id returns null`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)

        val declined = registry.decline(session.id)
        assertNotNull(declined)
        assertNull(registry.accept(session.id))
    }

    @Test
    fun `accept transitions PENDING to LIVE and second accept is a no-op`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)

        val first = registry.accept(session.id)
        assertNotNull(first)
        assertEquals(PvpSessionRegistry.Session.State.LIVE, first!!.state)
        // Second accept must observe the LIVE state and refuse.
        assertNull(registry.accept(session.id))
    }

    @Test
    fun `decline of LIVE session is rejected`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        // Once LIVE, decline is no-op — players use forfeit, not decline.
        assertNull(registry.decline(session.id))
    }

    @Test
    fun `recordPick stores both choices and bothPicked flips true`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)

        val afterFirst = registry.recordPick(session.id, initiatorId, RpsEngine.Choice.ROCK)
        assertNotNull(afterFirst)
        assertEquals(false, afterFirst!!.bothPicked)

        val afterSecond = registry.recordPick(session.id, opponentId, RpsEngine.Choice.PAPER)
        assertNotNull(afterSecond)
        assertEquals(true, afterSecond!!.bothPicked)
        assertEquals(RpsEngine.Choice.ROCK, afterSecond.picks[initiatorId])
        assertEquals(RpsEngine.Choice.PAPER, afterSecond.picks[opponentId])
    }

    @Test
    fun `recordPick by a non-player is rejected`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        assertNull(registry.recordPick(session.id, discordId = 9999L, RpsEngine.Choice.ROCK))
    }

    @Test
    fun `recordPick on a PENDING session is rejected`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        // No accept yet — session is still PENDING.
        assertNull(registry.recordPick(session.id, initiatorId, RpsEngine.Choice.ROCK))
    }

    @Test
    fun `consumeForResolution atomic-removes and second call returns null`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)
        registry.recordPick(session.id, initiatorId, RpsEngine.Choice.ROCK)
        registry.recordPick(session.id, opponentId, RpsEngine.Choice.PAPER)

        assertNotNull(registry.consumeForResolution(session.id))
        assertNull(registry.consumeForResolution(session.id))
    }

    @Test
    fun `forfeit atomic-removes and second call returns null`() {
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
        val session = registry.register(guildId, initiatorId, opponentId, stake = 10L)
        registry.accept(session.id)

        assertNotNull(registry.forfeit(session.id))
        assertNull(registry.forfeit(session.id))
    }

    @Test
    fun `concurrent accept across many threads produces exactly one success`() {
        // The synchronized + state-check pattern in accept() must
        // collapse N concurrent accepts to one winner. Otherwise a
        // double-debit is possible at the service layer.
        val registry = RpsSessionRegistry(scheduler = noopScheduler())
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
        assertEquals(1, successes.get(), "exactly one thread must observe the PENDING→LIVE transition")
    }

    @Test
    fun `pending timeout fires its callback exactly once when nothing else consumes the session`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            val registry = RpsSessionRegistry(
                pendingTtl = Duration.ofMillis(100),
                pickTtl = Duration.ofMinutes(5),
                scheduler = scheduler,
            )
            val fired = AtomicInteger(0)
            registry.register(guildId, initiatorId, opponentId, stake = 10L) { _ ->
                fired.incrementAndGet()
            }
            // Wide margin (1s for a 100ms TTL) so a slow CI runner can't
            // race the assertion ahead of the scheduled fire.
            Thread.sleep(1_000)
            assertEquals(1, fired.get(), "pending timeout must fire exactly once")
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `pending timeout is a no-op when the session has already accepted`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            val registry = RpsSessionRegistry(
                pendingTtl = Duration.ofMillis(100),
                pickTtl = Duration.ofMinutes(5),
                scheduler = scheduler,
            )
            val fired = AtomicInteger(0)
            val session = registry.register(guildId, initiatorId, opponentId, stake = 10L) { _ ->
                fired.incrementAndGet()
            }
            // Accept immediately — the still-pending pending-timeout
            // should observe LIVE and bail.
            registry.accept(session.id)
            Thread.sleep(1_000)
            assertEquals(0, fired.get(), "pending timeout must NOT fire once the session went LIVE")
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `Caffeine maximumSize cap evicts oldest entries to keep memory bounded`() {
        // Belt-and-suspenders memory protection: even with a flood of
        // /rps invocations, the cache caps at maximumSessions. Pin the
        // behaviour with a low cap so an accidental change to the
        // companion constant (or a regression to ConcurrentHashMap)
        // surfaces immediately.
        val registry = RpsSessionRegistry(
            scheduler = noopScheduler(),
            maximumSessions = 3L,
        )
        val ids = (1..10).map {
            registry.register(guildId, initiatorId + it, opponentId + it, stake = 0L).id
        }
        // Caffeine performs eviction asynchronously; nudge it with a
        // synchronous read so the cleanup completes before we assert.
        ids.forEach { registry.get(it) }
        Thread.sleep(50)
        val surviving = ids.count { registry.get(it) != null }
        assertTrue(surviving <= 3, "expected at most 3 survivors with maximumSessions=3, got $surviving")
    }

    /**
     * A no-op scheduler for tests that don't care about timeouts.
     * Avoids spinning up real threads when we only want to drive the
     * state machine directly.
     */
    private fun noopScheduler(): ScheduledExecutorService =
        object : ScheduledThreadPoolExecutor(0) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                // Drop the task on the floor — tests that need the timeout
                // create a real scheduler.
                return super.schedule(Runnable { /* no-op */ }, Long.MAX_VALUE, TimeUnit.SECONDS)
            }
        }
}
