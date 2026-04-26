package database.duel

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingDuelRegistryTest {

    private val scheduler = Executors.newScheduledThreadPool(2)

    @AfterEach
    fun shutdown() {
        scheduler.shutdownNow()
    }

    private fun newRegistry(ttl: Duration = Duration.ofMillis(50)): PendingDuelRegistry =
        PendingDuelRegistry(ttl = ttl, scheduler = scheduler)

    @Test
    fun `register returns increasing ids and stores the offer`() {
        val registry = newRegistry(ttl = Duration.ofSeconds(60))
        val a = registry.register(guildId = 1, initiatorDiscordId = 10, opponentDiscordId = 20, stake = 50L)
        val b = registry.register(guildId = 1, initiatorDiscordId = 11, opponentDiscordId = 21, stake = 75L)

        assertNotEquals(a.id, b.id)
        assertTrue(b.id > a.id)
        assertEquals(50L, registry.get(a.id)?.stake)
        assertEquals(75L, registry.get(b.id)?.stake)
    }

    @Test
    fun `consumeForAccept removes the offer and a second call returns null`() {
        val registry = newRegistry(ttl = Duration.ofSeconds(60))
        val offer = registry.register(1L, 10L, 20L, 50L)

        val first = registry.consumeForAccept(offer.id)
        val second = registry.consumeForAccept(offer.id)

        assertEquals(offer.id, first?.id)
        assertNull(second)
    }

    @Test
    fun `cancel removes the offer and consumeForAccept afterwards returns null`() {
        val registry = newRegistry(ttl = Duration.ofSeconds(60))
        val offer = registry.register(1L, 10L, 20L, 50L)

        val cancelled = registry.cancel(offer.id)
        val consumed = registry.consumeForAccept(offer.id)

        assertEquals(offer.id, cancelled?.id)
        assertNull(consumed)
    }

    @Test
    fun `expiry callback fires after the configured ttl when offer still pending`() {
        val registry = newRegistry(ttl = Duration.ofMillis(50))
        val latch = CountDownLatch(1)
        registry.register(1L, 10L, 20L, 50L) { _ -> latch.countDown() }

        assertTrue(latch.await(2, TimeUnit.SECONDS), "timeout callback should fire within ttl + slack")
    }

    @Test
    fun `expiry is a no-op after consumeForAccept`() {
        val registry = newRegistry(ttl = Duration.ofMillis(50))
        val callbackFired = java.util.concurrent.atomic.AtomicBoolean(false)
        val offer = registry.register(1L, 10L, 20L, 50L) { _ -> callbackFired.set(true) }

        // Consume immediately so the timer's later remove() returns null and skips the callback.
        registry.consumeForAccept(offer.id)
        Thread.sleep(150)

        // Either the timer fired and saw a null offer (callback skipped), or it didn't fire yet.
        // Either way, callback must not have fired.
        assertEquals(false, callbackFired.get(), "expiry callback must not run after consume")
    }

    @Test
    fun `pendingForOpponent returns only matching offers`() {
        val registry = newRegistry(ttl = Duration.ofSeconds(60))
        registry.register(1L, 10L, 20L, 50L)   // for opponent 20 in guild 1
        registry.register(1L, 11L, 20L, 75L)   // for opponent 20 in guild 1
        registry.register(2L, 12L, 20L, 25L)   // for opponent 20 in guild 2 — different guild
        registry.register(1L, 13L, 99L, 30L)   // different opponent

        val rows = registry.pendingForOpponent(20L, 1L)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.opponentDiscordId == 20L && it.guildId == 1L })
    }

    @Test
    fun `pendingForInitiator returns only matching offers`() {
        val registry = newRegistry(ttl = Duration.ofSeconds(60))
        registry.register(1L, 10L, 20L, 50L)   // initiator 10 in guild 1
        registry.register(1L, 10L, 21L, 75L)   // initiator 10 in guild 1, different opponent
        registry.register(2L, 10L, 22L, 25L)   // initiator 10 in guild 2 — different guild
        registry.register(1L, 99L, 23L, 30L)   // different initiator

        val rows = registry.pendingForInitiator(10L, 1L)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.initiatorDiscordId == 10L && it.guildId == 1L })
    }

    @Test
    fun `formatTtl renders short forms`() {
        assertEquals("3m", PendingDuelRegistry.formatTtl(Duration.ofMinutes(3)))
        assertEquals("45s", PendingDuelRegistry.formatTtl(Duration.ofSeconds(45)))
        assertEquals("1m 30s", PendingDuelRegistry.formatTtl(Duration.ofSeconds(90)))
        assertEquals("1h 5m", PendingDuelRegistry.formatTtl(Duration.ofMinutes(65)))
    }
}
