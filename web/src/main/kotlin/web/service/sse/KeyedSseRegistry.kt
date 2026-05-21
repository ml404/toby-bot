package web.service.sse

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Shared per-key SSE fan-out registry. Owns:
 *   - emitter storage (Caffeine cache of CopyOnWriteArrayList per key)
 *   - emitter lifecycle wiring (onCompletion / onTimeout / onError → evict)
 *   - the dead-emitter sweep that drops broken connections from a broadcast
 *   - a heartbeat helper that proxies past idle-connection killers
 *
 * Why Caffeine over a plain ConcurrentHashMap: when keys are user-scoped
 * (e.g. discordId) the entry count grows monotonically over JVM lifetime
 * unless empty buckets are dropped. We do drop empty buckets in the
 * lifecycle callbacks via [evictIfPresent], but Caffeine gives a
 * defence-in-depth bound: [Caffeine.maximumSize] caps the bucket count
 * absolutely and [Caffeine.expireAfterAccess] reaps any bucket that has
 * leaked past its lifecycle callbacks. The [removalListener] completes
 * any survivors so dropping the bucket also releases the underlying
 * SseEmitter resources.
 *
 * The registry is generic in [K] so the same plumbing serves both
 * `MusicSseService` (guildId keys) and `NotificationSseService`
 * (discordId keys). [bucketIsEmpty] / [forEachActiveKey] are exposed
 * for callers like the music-position scheduler that need to iterate
 * active keys without paying for buckets they don't care about.
 */
class KeyedSseRegistry<K : Any>(
    private val emitterTimeoutMs: Long = DEFAULT_EMITTER_TIMEOUT_MS,
    maximumKeys: Long = DEFAULT_MAXIMUM_KEYS,
    idleBucketTtl: Long = DEFAULT_IDLE_BUCKET_TTL_MIN,
    idleBucketTtlUnit: TimeUnit = TimeUnit.MINUTES,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val emitters: Cache<K, CopyOnWriteArrayList<SseEmitter>> = Caffeine.newBuilder()
        .expireAfterAccess(idleBucketTtl, idleBucketTtlUnit)
        .maximumSize(maximumKeys)
        .ticker(ticker)
        // Synchronous executor so the removal listener fires in the
        // calling thread. Default is ForkJoinPool.commonPool which makes
        // the listener race against any caller that wants to observe
        // completion synchronously (e.g. evict() returning before the
        // emitter has actually been completed). Listener work is just
        // `emitter.complete()` — cheap and safe to inline.
        .executor(Runnable::run)
        .removalListener<K, CopyOnWriteArrayList<SseEmitter>> { _, list, _ ->
            list?.forEach { runCatching { it.complete() } }
        }
        .build()

    /**
     * Register a new emitter for [key]. Sends an immediate `"hello"`
     * event carrying [helloPayload] so the client knows the channel is
     * live. The three lifecycle callbacks all evict via
     * [evictIfPresent], which drops the entire bucket when it becomes
     * empty — keeping the registry O(active keys), not O(historical keys).
     *
     * [emitter] is exposed as a default-arg seam so tests can inject a
     * mock that simulates broken-pipe / completed-emitter conditions
     * without going through the real servlet container. Production
     * callers should always omit this argument.
     */
    fun register(
        key: K,
        helloPayload: Any = emptyMap<String, Any>(),
        emitter: SseEmitter = SseEmitter(emitterTimeoutMs),
    ): SseEmitter {
        emitters.asMap().computeIfAbsent(key) { CopyOnWriteArrayList() }.add(emitter)
        emitter.onCompletion { evictIfPresent(key, emitter) }
        emitter.onTimeout {
            evictIfPresent(key, emitter)
            runCatching { emitter.complete() }
        }
        emitter.onError { evictIfPresent(key, emitter) }
        runCatching {
            emitter.send(SseEmitter.event().name(HELLO_EVENT).data(helloPayload))
        }.onFailure { evictIfPresent(key, emitter) }
        return emitter
    }

    /**
     * Broadcast a named event with [payload] to every emitter registered
     * under [key]. No-op when the bucket is absent — events for unknown
     * keys are silently dropped. Calls [Cache.getIfPresent] so the
     * access-time clock is refreshed (an active subscriber stays alive
     * even with no incoming events of its own).
     */
    fun fanOut(key: K, eventName: String, payload: Any) {
        val list = emitters.getIfPresent(key) ?: return
        broadcast(key, list) { send(SseEmitter.event().name(eventName).data(payload)) }
    }

    /**
     * Send a comment-frame heartbeat to every active emitter so idle
     * connections aren't torn down by intermediaries (e.g. Heroku's 55s
     * proxy timeout). Iterates [Cache.asMap] directly which does NOT
     * refresh access timers — buckets with no real traffic stay on
     * their natural expiry path.
     */
    fun heartbeat() {
        for ((key, list) in emitters.asMap()) {
            broadcast(key, list) { send(SseEmitter.event().comment("hb")) }
        }
    }

    /**
     * Drop every emitter for [key] and remove the bucket. The cache's
     * removal listener completes each surviving emitter so the network
     * resources are released, not just the registry slot.
     */
    fun evict(key: K) {
        emitters.invalidate(key)
    }

    /** True when [key] has no registered emitters (bucket absent or empty). */
    fun bucketIsEmpty(key: K): Boolean {
        val list = emitters.getIfPresent(key) ?: return true
        return list.isEmpty()
    }

    /** Iterate every currently-active key. Iteration does not refresh access timers. */
    fun forEachActiveKey(action: (K) -> Unit) {
        for (key in emitters.asMap().keys) action(key)
    }

    /**
     * Force pending Caffeine maintenance to run. Tests use this with an
     * injected [Ticker] to deterministically trigger time-based eviction;
     * production callers don't need it (Caffeine sweeps on every mutation).
     */
    internal fun cleanUp() {
        emitters.cleanUp()
    }

    /**
     * Read-only key view for tests that want to assert "is this key still
     * in the registry?" without mutating access timers. Production callers
     * should prefer [bucketIsEmpty] / [forEachActiveKey].
     */
    internal fun activeKeysSnapshot(): Set<K> = emitters.asMap().keys.toSet()

    private fun evictIfPresent(key: K, emitter: SseEmitter) {
        emitters.asMap().compute(key) { _, list ->
            list?.remove(emitter)
            if (list.isNullOrEmpty()) null else list
        }
    }

    private inline fun broadcast(
        key: K,
        list: CopyOnWriteArrayList<SseEmitter>,
        action: SseEmitter.() -> Unit,
    ) {
        if (list.isEmpty()) return
        val dead = mutableListOf<SseEmitter>()
        for (emitter in list) {
            try {
                emitter.action()
            } catch (_: IOException) {
                dead.add(emitter)
            } catch (_: IllegalStateException) {
                dead.add(emitter)
            }
        }
        if (dead.isEmpty()) return
        emitters.asMap().compute(key) { _, current ->
            current?.removeAll(dead.toSet())
            if (current.isNullOrEmpty()) null else current
        }
    }

    companion object {
        const val HELLO_EVENT = "hello"

        /** 1 hour. Long enough that browsers usually close first; reconnect logic on the client handles expiry. */
        const val DEFAULT_EMITTER_TIMEOUT_MS = 60L * 60L * 1000L

        /**
         * Absolute ceiling on concurrent keys. At ~80 bytes/entry plus a
         * CoW-list per entry this is a bounded ~megabyte even at the cap;
         * real concurrent-user counts are a tiny fraction. The cap is the
         * defence against a registry leak — if [evictIfPresent] ever
         * fails to fire (a fundamentally-broken emitter), the cache still
         * won't grow without bound.
         */
        const val DEFAULT_MAXIMUM_KEYS = 10_000L

        /** Idle bucket TTL. Belt-and-braces alongside the per-emitter lifecycle eviction. */
        const val DEFAULT_IDLE_BUCKET_TTL_MIN = 120L
    }
}
