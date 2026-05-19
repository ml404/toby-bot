package common.helpers

import org.apache.commons.collections4.MapIterator
import org.apache.commons.collections4.map.LRUMap

class Cache(
    timeToLiveInSeconds: Long, timerIntervalInSeconds: Long,
    maxItems: Int
) {
    private val timeToLiveInMillis = timeToLiveInSeconds * 1000

    private val cacheMap: LRUMap<String, CachedObject> = LRUMap(maxItems)

    private class CachedObject(var value: List<String>) {
        var lastAccessed: Long = System.currentTimeMillis()
    }

    init {

        if (timeToLiveInMillis > 0 && timerIntervalInSeconds > 0) {
            val t = Thread {
                while (true) {
                    try {
                        Thread.sleep(timerIntervalInSeconds * 1000)
                    } catch (ignored: InterruptedException) {
                    }

                    cleanup()
                }
            }

            t.isDaemon = true
            t.start()
        }
    }

    fun put(key: String, value: List<String>) {
        synchronized(cacheMap) {
            cacheMap.put(key, CachedObject(value))
        }
    }

    fun get(key: String): List<String>? {
        synchronized(cacheMap) {
            val c = cacheMap[key]
            if (c == null) return null
            else {
                c.lastAccessed = System.currentTimeMillis()
                return c.value
            }
        }
    }

    fun remove(key: String) {
        synchronized(cacheMap) {
            cacheMap.remove(key)
        }
    }

    fun size(): Int {
        synchronized(cacheMap) {
            return cacheMap.size
        }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        // Hold the lock across identify+evict so a concurrent put/get on an entry
        // we'd mark as stale can't slip in a refreshed lastAccessed between the
        // scan and the remove. Sweeping is bounded by maxItems.
        synchronized(cacheMap) {
            val itr: MapIterator<String, CachedObject> = cacheMap.mapIterator()
            while (itr.hasNext()) {
                itr.next()
                val c = itr.value
                if (c != null && now > timeToLiveInMillis + c.lastAccessed) {
                    itr.remove()
                }
            }
        }
    }
}
