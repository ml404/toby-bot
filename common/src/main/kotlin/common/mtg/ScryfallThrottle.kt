package common.mtg

import java.time.Duration

/**
 * Paces outbound Scryfall calls. Scryfall asks API clients to leave 50–100ms
 * between requests; neither surface did, and one request can fan out to ten
 * of them (paged searches, or a 750-name list resolved 75 at a time).
 *
 * Shared by both callers of Scryfall — the website's `CubeWebService` and the
 * bot's `ScryfallCubeFetcher` — so one bot process presents one paced client
 * rather than two that each think they're the only one.
 *
 * Callers reserve a slot rather than hold a lock: [reserveSlotMillis] works
 * out when this request may go out and claims that instant, and the waiting
 * happens **outside** the lock, so concurrent requests stagger instead of
 * serialising on each other's network round trip.
 *
 * The waiting is the caller's to do, because the two surfaces wait
 * differently: the web blocks a request thread ([awaitSlot]), while the bot
 * is coroutine-based and wants `delay()`. Keeping the arithmetic here and the
 * sleeping there means `common` needs no coroutines dependency.
 *
 * A request that would have to wait longer than [maxWait] is refused instead
 * of parking a caller indefinitely — under a burst it's better to say "try
 * again" than to queue someone behind a minute of backlog.
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
     * Claims this caller's turn and returns how long it must wait before
     * sending, in milliseconds (often 0). Returns null when the queue is
     * already longer than [maxWait] — no slot is claimed, and the caller
     * should give up rather than wait.
     *
     * The caller does the waiting: block on it, or `delay()` on it.
     */
    fun reserveSlotMillis(): Long? {
        synchronized(lock) {
            val now = clock()
            val slot = maxOf(now, nextSlot)
            val wait = slot - now
            if (wait > maxWait.toNanos()) return null
            nextSlot = slot + minInterval.toNanos()
            return wait / 1_000_000
        }
    }

    /**
     * [reserveSlotMillis] plus a blocking wait — for callers on a thread they
     * can afford to park. Returns false when the call was refused.
     */
    fun awaitSlot(): Boolean {
        val waitMillis = reserveSlotMillis() ?: return false
        if (waitMillis > 0) sleeper(waitMillis)
        return true
    }
}
