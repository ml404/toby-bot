package database.service

import database.dto.MessageDailyCountDto
import database.persistence.MessageDailyCountPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import database.service.activity.MessageActivityBuffer

/**
 * Tests for the in-memory message-counter buffer. The scheduled flush
 * path is exercised via direct [MessageActivityBuffer.flush] calls so
 * the tests don't sleep on a real wall-clock.
 */
class MessageActivityBufferTest {

    private lateinit var persistence: RecordingPersistence
    private lateinit var buffer: MessageActivityBuffer

    private val fixedDay: LocalDate = LocalDate.of(2026, 5, 23)
    private val fixedClock: Clock = Clock.fixed(
        fixedDay.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60 * 60 * 12),
        ZoneOffset.UTC,
    )

    @BeforeEach
    fun setUp() {
        persistence = RecordingPersistence()
        buffer = MessageActivityBuffer(
            persistence = persistence,
            clock = fixedClock,
            scheduler = noopScheduler(),
        )
    }

    @Test
    fun `record increments the in-memory counter and flush drains it once`() {
        buffer.record(guildId = 100L)
        buffer.record(guildId = 100L)
        buffer.record(guildId = 100L)

        // Nothing flushed yet — persistence is untouched.
        assertEquals(0, persistence.increments.size)

        buffer.flush()
        assertEquals(listOf(Triple(100L, fixedDay, 3L)), persistence.increments)

        // Second flush with no new records is a no-op.
        persistence.increments.clear()
        buffer.flush()
        assertEquals(emptyList<Triple<Long, LocalDate, Long>>(), persistence.increments)
    }

    @Test
    fun `record buckets per guild and per day`() {
        buffer.record(guildId = 100L)
        buffer.record(guildId = 200L)
        buffer.record(guildId = 100L)
        buffer.flush()
        assertEquals(2, persistence.increments.size)
        val byGuild = persistence.increments.associate { it.first to it.third }
        assertEquals(2L, byGuild[100L])
        assertEquals(1L, byGuild[200L])
    }

    @Test
    fun `record under concurrent load doesn't lose increments`() {
        // 8 threads × 1000 records each → 8000 records into one guild.
        // The buffer flushes once at the end; total must equal the input.
        val threads = 8
        val perThread = 1000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) {
            pool.submit {
                start.await()
                repeat(perThread) { buffer.record(guildId = 100L) }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        buffer.flush()
        val total = persistence.increments.sumOf { it.third }
        assertEquals((threads * perThread).toLong(), total)
    }

    @Test
    fun `flush retries on persistence failure by restoring the delta`() {
        buffer.record(guildId = 100L)
        buffer.record(guildId = 100L)
        persistence.failNext = 1

        buffer.flush()
        // The failed upsert rolled back into the buffer, so the second flush retries.
        assertTrue(persistence.increments.isEmpty(), "first flush failure should not record")
        buffer.flush()
        assertEquals(listOf(Triple(100L, fixedDay, 2L)), persistence.increments)
    }

    private class RecordingPersistence : MessageDailyCountPersistence {
        val increments = mutableListOf<Triple<Long, LocalDate, Long>>()
        var failNext: Int = 0

        override fun findByGuildSince(guildId: Long, since: LocalDate): List<MessageDailyCountDto> =
            emptyList()

        override fun increment(guildId: Long, dayStart: LocalDate, delta: Long) {
            if (failNext > 0) {
                failNext -= 1
                throw IllegalStateException("simulated persistence failure")
            }
            increments.add(Triple(guildId, dayStart, delta))
        }
    }

    private fun noopScheduler(): ScheduledExecutorService =
        object : ScheduledThreadPoolExecutor(0) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                super.schedule(Runnable { /* no-op */ }, Long.MAX_VALUE, TimeUnit.SECONDS)
            override fun scheduleAtFixedRate(
                command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit,
            ): ScheduledFuture<*> =
                super.schedule(Runnable { /* no-op */ }, Long.MAX_VALUE, TimeUnit.SECONDS)
        }
}
