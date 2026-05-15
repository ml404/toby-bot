package database.duel

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RecentDuelResolutionsTest {

    private val guildId = 42L
    private val initiator = 100L
    private val opponent = 200L

    private lateinit var scheduler: ScheduledExecutorService
    private val capturedTasks = mutableListOf<Runnable>()
    private val capturedDelays = mutableListOf<Long>()

    @BeforeEach
    fun setup() {
        scheduler = mockk(relaxed = true)
        capturedTasks.clear()
        capturedDelays.clear()
        every {
            scheduler.schedule(any<Runnable>(), any(), any<TimeUnit>())
        } answers {
            capturedTasks.add(firstArg())
            capturedDelays.add(secondArg<Long>())
            mockk(relaxed = true)
        }
    }

    private fun cache(ttl: Duration = Duration.ofSeconds(30)) =
        RecentDuelResolutions(ttl = ttl, scheduler = scheduler)

    private fun sample(
        guild: Long = guildId,
        init: Long = initiator,
        opp: Long = opponent,
        winner: Long = initiator,
        pot: Long = 100L,
        tribute: Long = 10L,
        at: Instant = Instant.parse("2026-04-10T10:00:00Z"),
    ) = RecentDuelResolutions.Resolution(
        guildId = guild,
        initiatorDiscordId = init,
        opponentDiscordId = opp,
        winnerDiscordId = winner,
        loserDiscordId = if (winner == init) opp else init,
        stake = pot / 2,
        pot = pot,
        lossTribute = tribute,
        resolvedAt = at,
    )

    @Test
    fun `record then consumeForInitiator returns the stored entry`() {
        val cache = cache()
        val entry = sample()
        cache.record(entry)

        val out = cache.consumeForInitiator(initiator, guildId)

        assertEquals(1, out.size)
        assertEquals(entry, out[0])
    }

    @Test
    fun `second consume returns empty - reads are once-only`() {
        val cache = cache()
        cache.record(sample())

        cache.consumeForInitiator(initiator, guildId)
        val again = cache.consumeForInitiator(initiator, guildId)

        assertTrue(again.isEmpty())
    }

    @Test
    fun `entries for other initiators are not returned`() {
        val cache = cache()
        cache.record(sample(init = 999L))
        cache.record(sample())

        val out = cache.consumeForInitiator(initiator, guildId)

        assertEquals(1, out.size)
        assertEquals(initiator, out[0].initiatorDiscordId)
    }

    @Test
    fun `entries from other guilds are not returned`() {
        val cache = cache()
        cache.record(sample(guild = 999L))
        cache.record(sample())

        val out = cache.consumeForInitiator(initiator, guildId)

        assertEquals(1, out.size)
        assertEquals(guildId, out[0].guildId)
    }

    @Test
    fun `multiple entries for the same initiator come back oldest-first`() {
        val cache = cache()
        val older = sample(at = Instant.parse("2026-04-10T10:00:00Z"), pot = 50L)
        val newer = sample(at = Instant.parse("2026-04-10T10:00:30Z"), pot = 70L)
        // Insert newer first so we know order is by resolvedAt, not insertion.
        cache.record(newer)
        cache.record(older)

        val out = cache.consumeForInitiator(initiator, guildId)

        assertEquals(2, out.size)
        assertEquals(50L, out[0].pot)
        assertEquals(70L, out[1].pot)
    }

    @Test
    fun `record schedules an eviction at ttl millis`() {
        val cache = cache(ttl = Duration.ofSeconds(10))
        cache.record(sample())

        assertEquals(1, capturedTasks.size)
        assertEquals(Duration.ofSeconds(10).toMillis(), capturedDelays[0])
    }

    @Test
    fun `TTL eviction removes an unconsumed entry`() {
        val cache = cache()
        cache.record(sample())

        // Fire the scheduled eviction.
        capturedTasks[0].run()

        assertTrue(cache.consumeForInitiator(initiator, guildId).isEmpty())
    }

    @Test
    fun `TTL eviction is a no-op when the entry was already consumed`() {
        val cache = cache()
        cache.record(sample())
        cache.consumeForInitiator(initiator, guildId)

        // Should not throw, and should leave the cache empty.
        capturedTasks[0].run()
        assertTrue(cache.consumeForInitiator(initiator, guildId).isEmpty())
    }
}
