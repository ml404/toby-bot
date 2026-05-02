package database.poker

import common.testing.DeterministicScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the per-table shot-clock plumbing in [PokerTableRegistry]
 * directly, without the [database.service.PokerService] auto-fold
 * path. The clock is a small piece of scheduler bookkeeping that
 * deserves coverage on its own — the production callback (auto-fold
 * via PokerService) is exercised separately by the service tests.
 *
 * Uses [DeterministicScheduler] so the tests don't sleep on a real
 * wall clock waiting for the executor to fire — they advance work
 * by calling [DeterministicScheduler.runPending].
 */
class PokerTableRegistryShotClockTest {

    private val scheduler = DeterministicScheduler()

    private fun newRegistry() = PokerTableRegistry(
        idleTtl = Duration.ofMinutes(10),
        sweepInterval = Duration.ofHours(1),
        scheduler = scheduler
    )

    private fun PokerTableRegistry.createTestTable(shotClockSeconds: Int = 30): PokerTable =
        create(
            guildId = 1L,
            hostDiscordId = 7L,
            minBuyIn = 100L,
            maxBuyIn = 5000L,
            smallBlind = 5L,
            bigBlind = 10L,
            smallBet = 10L,
            bigBet = 20L,
            maxRaisesPerStreet = 4,
            maxSeats = 6,
            shotClockSeconds = shotClockSeconds
        )

    @Test
    fun `rearmShotClock is a no-op when the table has the clock disabled`() {
        val reg = newRegistry()
        val table = reg.createTestTable(shotClockSeconds = 0)
        // Even with phase != WAITING, a 0 clock means no deadline and no fire.
        table.phase = PokerTable.Phase.PRE_FLOP
        reg.rearmShotClock(table.id)
        assertNull(table.currentActorDeadline, "0-second clock leaves the deadline null")
    }

    @Test
    fun `rearmShotClock leaves deadline null when the table is WAITING`() {
        val reg = newRegistry()
        val table = reg.createTestTable(shotClockSeconds = 30)
        // Brand-new table sits in WAITING — no actor to clock.
        reg.rearmShotClock(table.id)
        assertNull(table.currentActorDeadline)
    }

    @Test
    fun `rearmShotClock sets currentActorDeadline based on now plus shot clock`() {
        val reg = newRegistry()
        val table = reg.createTestTable(shotClockSeconds = 30)
        table.phase = PokerTable.Phase.PRE_FLOP
        val now = Instant.parse("2026-05-01T10:00:00Z")
        reg.rearmShotClock(table.id, now)
        assertEquals(now.plusSeconds(30), table.currentActorDeadline)
    }

    @Test
    fun `cancelShotClock clears the pending task and is idempotent`() {
        val reg = newRegistry()
        val table = reg.createTestTable(shotClockSeconds = 30)
        table.phase = PokerTable.Phase.PRE_FLOP
        reg.rearmShotClock(table.id)
        reg.cancelShotClock(table.id)
        // Calling cancel again must not throw.
        reg.cancelShotClock(table.id)
        // A second cancel is harmless even when nothing is armed.
        reg.cancelShotClock(table.id + 99)
    }

    @Test
    fun `clock fires onShotClockExpired when the actor doesn't act in time`() {
        val reg = newRegistry()
        val firedTable = AtomicReference<PokerTable?>(null)
        reg.setOnShotClockExpired { t -> firedTable.set(t) }

        val table = reg.createTestTable(shotClockSeconds = 1)
        table.phase = PokerTable.Phase.PRE_FLOP
        table.handNumber = 1L
        reg.rearmShotClock(table.id)

        scheduler.runPending()

        assertNotNull(firedTable.get(), "shot clock did not fire")
        assertEquals(table.id, firedTable.get()!!.id)
    }

    @Test
    fun `clock does NOT fire if the actor acted before the deadline`() {
        val reg = newRegistry()
        val fireCount = java.util.concurrent.atomic.AtomicInteger(0)
        reg.setOnShotClockExpired { _ -> fireCount.incrementAndGet() }

        val table = reg.createTestTable(shotClockSeconds = 1)
        table.phase = PokerTable.Phase.PRE_FLOP
        table.handNumber = 1L
        reg.rearmShotClock(table.id)
        reg.cancelShotClock(table.id)

        scheduler.runPending()

        assertEquals(0, fireCount.get(), "clock fired despite cancellation")
    }

    @Test
    fun `stale clock task is suppressed when the actor has moved on`() {
        // A clock fires for actor=0 hand=1, but by the time the
        // scheduled task runs we've already advanced to actor=1.
        // The registry must detect the staleness and suppress the
        // callback rather than auto-folding the wrong actor.
        val reg = newRegistry()
        val fireCount = java.util.concurrent.atomic.AtomicInteger(0)
        reg.setOnShotClockExpired { _ -> fireCount.incrementAndGet() }

        val table = reg.createTestTable(shotClockSeconds = 1)
        table.phase = PokerTable.Phase.PRE_FLOP
        table.handNumber = 1L
        table.actorIndex = 0
        reg.rearmShotClock(table.id)

        // Simulate the actor acting (advancing actorIndex) without
        // cancelling — the in-flight task should detect the change.
        table.actorIndex = 1

        scheduler.runPending()

        assertEquals(0, fireCount.get(), "stale fire was not suppressed (count=${fireCount.get()})")
    }

    @Test
    fun `sweepIdle cancels the shot clock for evicted tables`() {
        val reg = PokerTableRegistry(
            idleTtl = Duration.ofMillis(10),
            sweepInterval = Duration.ofHours(1),
            scheduler = scheduler
        )
        val fireCount = java.util.concurrent.atomic.AtomicInteger(0)
        reg.setOnShotClockExpired { _ -> fireCount.incrementAndGet() }

        val table = reg.create(
            guildId = 1L, hostDiscordId = 7L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L, smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
            shotClockSeconds = 1,
            now = Instant.now().minus(Duration.ofMinutes(1))
        )
        table.phase = PokerTable.Phase.PRE_FLOP
        table.handNumber = 1L
        reg.rearmShotClock(table.id)

        // The table is already past its idle TTL — sweep evicts it
        // and should cancel the pending shot-clock task.
        reg.sweepIdle(Instant.now())

        scheduler.runPending()

        assertEquals(0, fireCount.get(), "shot clock fired for an evicted table")
    }
}
