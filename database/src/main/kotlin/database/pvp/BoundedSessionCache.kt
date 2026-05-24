package database.pvp

import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.concurrent.ConcurrentMap

/**
 * Shared factory for the bounded-and-TTL'd Caffeine map convention used
 * by the PvP session registries. Every registry that holds short-lived
 * in-memory state for in-flight matches wants the same two protections
 * against runaway memory: a hard upper bound on entries
 * ([maxEntries]) so a flood can't OOM the heap, and a backstop TTL
 * ([maxLifetime]) so a scheduled-task drop can't leak entries past
 * their useful life.
 *
 * Returns the `asMap()` view so call sites can keep using the familiar
 * [ConcurrentMap] primitives (atomic put/remove, values iteration). The
 * underlying Caffeine cache stays GC-alive via the returned map's back-
 * reference — no need for the caller to retain the cache handle
 * separately.
 */
object BoundedSessionCache {
    fun <K : Any, V : Any> build(maxLifetime: Duration, maxEntries: Long): ConcurrentMap<K, V> =
        Caffeine.newBuilder()
            .expireAfterWrite(maxLifetime)
            .maximumSize(maxEntries)
            .build<K, V>()
            .asMap()
}
