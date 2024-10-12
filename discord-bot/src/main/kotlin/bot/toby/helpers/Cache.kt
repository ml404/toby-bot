package bot.toby.helpers

import org.apache.commons.collections4.MapIterator
import org.apache.commons.collections4.map.LRUMap

open class Cache(
    timeToLiveInSeconds: Long, timerIntervalInSeconds: Long,
    maxItems: Int
) {
    private val timeToLiveInMillis = timeToLiveInSeconds * 1000

    private val cacheMap: LRUMap<String, CachedObject> = LRUMap(maxItems)

    protected inner class CachedObject(var value: List<String>) {
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
        var keysToDelete: ArrayList<String>

        synchronized(cacheMap) {
            val itr: MapIterator<String, CachedObject> = cacheMap.mapIterator()
            keysToDelete = ArrayList((cacheMap.size / 2) + 1)
            var key: String
            var c: CachedObject?
            while (itr.hasNext()) {
                key = itr.next()
                c = itr.value

                if (c != null && (now > (timeToLiveInMillis + c.lastAccessed))) {
                    keysToDelete.add(key)
                }
            }
        }

        for (key in keysToDelete) {
            synchronized(cacheMap) {
                cacheMap.remove(key)
            }

            Thread.yield()
        }
    }
}
