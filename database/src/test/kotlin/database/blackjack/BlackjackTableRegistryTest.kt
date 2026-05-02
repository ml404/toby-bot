package database.blackjack

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

class BlackjackTableRegistryTest {

    private fun newRegistry() = BlackjackTableRegistry(
        idleTtl = Duration.ofMinutes(5),
        sweepInterval = Duration.ofHours(1) // effectively disabled in tests
    )

    private fun BlackjackTableRegistry.makeSolo(guildId: Long = 42L, host: Long = 1L) =
        create(
            guildId = guildId,
            mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = host,
            ante = 100L,
            maxSeats = 1
        )

    private fun BlackjackTableRegistry.makeMulti(guildId: Long = 42L, host: Long = 1L) =
        create(
            guildId = guildId,
            mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = host,
            ante = 100L,
            maxSeats = 5
        )

    @Test
    fun `create assigns increasing ids`() {
        val reg = newRegistry()
        val t1 = reg.makeSolo()
        val t2 = reg.makeMulti()
        assertNotEquals(t1.id, t2.id)
        assertTrue(t2.id > t1.id)
    }

    @Test
    fun `get returns the registered table`() {
        val reg = newRegistry()
        val t = reg.makeMulti()
        assertEquals(t, reg.get(t.id))
        assertNull(reg.get(t.id + 999))
    }

    @Test
    fun `listForGuild filters by guildId`() {
        val reg = newRegistry()
        val a = reg.makeMulti(guildId = 1L)
        val b = reg.makeSolo(guildId = 1L)
        reg.makeMulti(guildId = 2L) // different guild
        val list = reg.listForGuild(1L)
        assertEquals(setOf(a.id, b.id), list.map { it.id }.toSet())
    }

    @Test
    fun `remove drops the table from the registry`() {
        val reg = newRegistry()
        val t = reg.makeMulti()
        reg.remove(t.id)
        assertNull(reg.get(t.id))
    }

    @Test
    fun `lockTable returns null after table removed`() {
        val reg = newRegistry()
        val t = reg.makeMulti()
        reg.remove(t.id)
        assertNull(reg.lockTable(t.id) { 7 })
    }

    @Test
    fun `lockTable serialises mutations across threads`() {
        val reg = newRegistry()
        val t = reg.makeMulti()
        val counter = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val seen = mutableListOf<Int>()
        try {
            val tasks = List(100) {
                java.util.concurrent.Callable {
                    reg.lockTable(t.id) { table ->
                        val v = counter.incrementAndGet()
                        synchronized(seen) { seen.add(v) }
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
        for (i in 1 until seen.size) {
            assertTrue(seen[i] > seen[i - 1], "out-of-order observation at index $i")
        }
    }

    @Test
    fun `sweepIdle evicts stale tables and invokes callback`() {
        val reg = BlackjackTableRegistry(
            idleTtl = Duration.ofSeconds(10),
            sweepInterval = Duration.ofHours(1)
        )
        val evicted = mutableListOf<Long>()
        reg.setOnIdleEvict { table -> evicted.add(table.id) }

        val stale = reg.makeMulti()
        stale.lastActivityAt = Instant.now().minus(Duration.ofMinutes(1))
        val fresh = reg.makeMulti()
        fresh.lastActivityAt = Instant.now()

        reg.sweepIdle(Instant.now())
        assertNull(reg.get(stale.id), "stale table should be removed")
        assertNotNull(reg.get(fresh.id), "fresh table survives the sweep")
        assertEquals(listOf(stale.id), evicted)
    }
}
