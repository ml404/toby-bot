package database.service.activity

import database.configuration.RegistryScheduler
import database.persistence.activity.MessageDailyCountPersistence
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory accumulator for per-guild per-day message counts. The
 * `MessageChatListener` calls [record] on every non-bot message; a
 * scheduled job flushes the buffer to [MessageDailyCountPersistence]
 * every [flushInterval]. This is the same shape `MessageChatListener`
 * already uses for XP-award debouncing — no per-message round-trip to
 * Postgres, no risk of blocking the JDA dispatch thread on a slow DB.
 *
 * The buffer is keyed on `(guildId, dayStart)` using UTC dates so the
 * cutover from one day to the next is consistent regardless of where
 * the bot is hosted. A message sent at 23:59 UTC and the next message
 * at 00:01 UTC land in different days.
 *
 * Lost-on-restart cost: any messages buffered between the last flush
 * and a shutdown are dropped. Acceptable for a coarse activity chart;
 * the worst case (a hard kill seconds after a flush) loses the same
 * order of magnitude the XP cooldown loses on the same path.
 */
@Component
class MessageActivityBuffer(
    private val persistence: MessageDailyCountPersistence,
    private val clock: Clock = Clock.systemUTC(),
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
) {

    private val pending = ConcurrentHashMap<Pair<Long, LocalDate>, AtomicLong>()
    private var flushTask: ScheduledFuture<*>? = null

    @PostConstruct
    fun start() {
        if (flushTask != null) return
        flushTask = scheduler.scheduleAtFixedRate(
            { runCatching { flush() } },
            flushInterval.toMillis(),
            flushInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    @PreDestroy
    fun stop() {
        flushTask?.cancel(false)
        flushTask = null
        // Best-effort drain on shutdown so the very last batch isn't lost.
        runCatching { flush() }
    }

    /** Record one message for [guildId]. Bucketed under today's UTC date. */
    fun record(guildId: Long) {
        val day = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        pending.computeIfAbsent(guildId to day) { AtomicLong(0L) }.incrementAndGet()
    }

    /**
     * Drain the buffer into the persistence layer. Atomically swaps
     * each `(guildId, day)` counter to 0 before issuing the upsert so a
     * concurrent [record] call doesn't lose increments. Public for tests;
     * production code goes through the scheduled task.
     */
    fun flush() {
        // Snapshot keys first — `pending.keys` is a live view, so iterating
        // while incrementing would behave oddly if we removed entries we'd
        // already counted.
        val keys = pending.keys.toList()
        for (key in keys) {
            val counter = pending[key] ?: continue
            val delta = counter.getAndSet(0L)
            if (delta <= 0L) continue
            runCatching { persistence.increment(key.first, key.second, delta) }
                .onFailure { restore(key, delta) }
        }
    }

    private fun restore(key: Pair<Long, LocalDate>, delta: Long) {
        // Persistence rejected the upsert — roll the delta back into the
        // buffer so the next flush retries it instead of dropping the count.
        pending.computeIfAbsent(key) { AtomicLong(0L) }.addAndGet(delta)
    }

    companion object {
        /**
         * Once per minute keeps the chart fresh enough that admins see
         * "today" tick up in near-real-time without hammering Postgres
         * on a busy guild.
         */
        val DEFAULT_FLUSH_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
