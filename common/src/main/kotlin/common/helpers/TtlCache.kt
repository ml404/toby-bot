package common.helpers

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * A small time- and size-bounded cache for Scryfall responses.
 *
 * Draft night is the case this exists for: eight people open the same shared
 * cube within a minute of each other and every one of them re-resolved the
 * same 540 names from scratch. Entries are held briefly — long enough to
 * cover that burst, short enough that a cube edited on the website shows its
 * new cards on the next go.
 *
 * Bounded by [maxEntries] because a pooled entry can hold hundreds of cards;
 * when full, the whole map is dropped rather than tracking per-entry access
 * order, which for a cache this small costs less than it saves.
 */
class TtlCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttl: Duration,
    private val clock: () -> Long = System::nanoTime,
) {

    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    /** The cached value for [key], or null when absent or stale. */
    operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= clock()) {
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }

    operator fun set(key: K, value: V) {
        if (entries.size >= maxEntries) entries.clear()
        entries[key] = Entry(value, clock() + ttl.toNanos())
    }

    /** [get], falling back to [supplier] and caching a non-null result. */
    fun getOrPut(key: K, supplier: () -> V?): V? {
        get(key)?.let { return it }
        return supplier()?.also { this[key] = it }
    }

    val size: Int get() = entries.size
}
