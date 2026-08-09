package web.service

import java.time.Duration

/**
 * Paces outbound Scryfall calls. Scryfall asks API clients to leave 50–100ms
 * between requests; nothing here did, and a single anonymous `/magic/api/...`
 * call can fan out to ten of them (paged searches, or a 750-name list
 * resolved 75 at a time).
 *
 * Callers reserve a slot rather than hold a lock: [awaitSlot] works out when
 * this request may go out, claims that instant, and sleeps **outside** the
 * lock, so concurrent requests stagger instead of serialising on each
 * other's network round trip.
 *
 * A request that would have to wait longer than [maxWait] is refused instead
 * of parking a servlet thread indefinitely — under a burst it's better to
 * tell someone to try again than to queue them behind a minute of backlog.
 */
class ScryfallThrottle(
    private val minInterval: Duration = Duration.ofMillis(100),
    private val maxWait: Duration = Duration.ofSeconds(3),
    private val clock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {

    private val lock = Any()

    /** The instant (in [clock] ticks) the next request may be sent. */
    private var nextSlot: Long = Long.MIN_VALUE

    /**
     * Waits for this caller's turn. Returns false when the queue is already
     * longer than [maxWait], in which case no slot is claimed and the caller
     * should give up rather than wait.
     */
    fun awaitSlot(): Boolean {
        val waitNanos = synchronized(lock) {
            val now = clock()
            val slot = maxOf(now, nextSlot)
            val wait = slot - now
            if (wait > maxWait.toNanos()) return false
            nextSlot = slot + minInterval.toNanos()
            wait
        }
        if (waitNanos > 0) sleeper(waitNanos / 1_000_000)
        return true
    }
}
