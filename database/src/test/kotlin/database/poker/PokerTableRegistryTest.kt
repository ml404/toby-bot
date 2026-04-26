package database.poker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PokerTableRegistryTest {

    private fun newRegistry() = PokerTableRegistry(
        idleTtl = Duration.ofMinutes(5),
        sweepInterval = Duration.ofHours(1) // effectively disabled in tests
    )

    private fun PokerTableRegistry.makeTable(guildId: Long = 42L, host: Long = 1L) =
        create(
            guildId = guildId,
            hostDiscordId = host,
            minBuyIn = 100L,
            maxBuyIn = 5000L,
            smallBlind = 5L,
            bigBlind = 10L,
            smallBet = 10L,
            bigBet = 20L,
            maxRaisesPerStreet = 4,
            maxSeats = 6,
        )

    @Test
    fun `create assigns increasing ids`() {
        val reg = newRegistry()
        val t1 = reg.makeTable()
        val t2 = reg.makeTable()
        assertNotEquals(t1.id, t2.id)
        assertTrue(t2.id > t1.id)
    }

    @Test
    fun `get returns the registered table`() {
        val reg = newRegistry()
        val t = reg.makeTable()
        assertEquals(t, reg.get(t.id))
        assertNull(reg.get(t.id + 999))
    }

    @Test
    fun `listForGuild filters by guildId`() {
        val reg = newRegistry()
        val a = reg.makeTable(guildId = 1L)
        val b = reg.makeTable(guildId = 1L)
        reg.makeTable(guildId = 2L) // different guild
        val list = reg.listForGuild(1L)
        assertEquals(setOf(a.id, b.id), list.map { it.id }.toSet())
    }

    @Test
    fun `remove drops the table from the registry`() {
        val reg = newRegistry()
        val t = reg.makeTable()
        reg.remove(t.id)
        assertNull(reg.get(t.id))
    }

    @Test
    fun `lockTable serialises mutations across threads`() {
        val reg = newRegistry()
        val t = reg.makeTable()
        val counter = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val seen = mutableListOf<Int>()
        try {
            val tasks = List(100) {
                java.util.concurrent.Callable {
                    reg.lockTable(t.id) { table ->
                        // Inside the monitor: unsynchronized increment must produce
                        // monotonically-strictly-increasing observations because no
                        // two callers can be inside the block at once.
                        val v = counter.incrementAndGet()
                        synchronized(seen) { seen.add(v) }
                        // Touch the table to prove we have the live reference.
                        table.lastActivityAt = Instant.now()
                        v
                    }
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(100, seen.size)
        // Strictly increasing — no two threads ran the body in parallel.
        for (i in 1 until seen.size) {
            assertTrue(seen[i] > seen[i - 1], "out-of-order observation at index $i: ${seen.subList(0, i+1)}")
        }
    }

    @Test
    fun `sweepIdle evicts stale tables and invokes callback`() {
        val reg = PokerTableRegistry(
            idleTtl = Duration.ofSeconds(10),
            sweepInterval = Duration.ofHours(1)
        )
        val evicted = mutableListOf<Long>()
        reg.setOnIdleEvict { table -> evicted.add(table.id) }

        val stale = reg.makeTable()
        // Force the lastActivityAt back in time.
        stale.lastActivityAt = Instant.now().minus(Duration.ofMinutes(1))
        val fresh = reg.makeTable()
        fresh.lastActivityAt = Instant.now()

        reg.sweepIdle(Instant.now())
        assertNull(reg.get(stale.id), "stale table should be removed")
        assertNotNull(reg.get(fresh.id), "fresh table survives the sweep")
        assertEquals(listOf(stale.id), evicted)
    }

    @Test
    fun `lockTable returns null after table removed`() {
        val reg = newRegistry()
        val t = reg.makeTable()
        reg.remove(t.id)
        val r = reg.lockTable(t.id) { 7 }
        assertNull(r)
    }
}
