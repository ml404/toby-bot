package common.helpers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CacheTest {

    @Test
    fun `put then get returns the stored value`() {
        val cache = newCacheNoSweeper()
        cache.put("k", listOf("v1", "v2"))
        assertEquals(listOf("v1", "v2"), cache.get("k"))
    }

    @Test
    fun `get returns null for missing key`() {
        val cache = newCacheNoSweeper()
        assertNull(cache.get("absent"))
    }

    @Test
    fun `put overwrites existing value`() {
        val cache = newCacheNoSweeper()
        cache.put("k", listOf("first"))
        cache.put("k", listOf("second"))
        assertEquals(listOf("second"), cache.get("k"))
    }

    @Test
    fun `remove drops the entry`() {
        val cache = newCacheNoSweeper()
        cache.put("k", listOf("v"))
        cache.remove("k")
        assertNull(cache.get("k"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `size reflects entry count`() {
        val cache = newCacheNoSweeper()
        assertEquals(0, cache.size())
        cache.put("a", listOf("1"))
        cache.put("b", listOf("2"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `LRU eviction discards the oldest entry once max items exceeded`() {
        val cache = Cache(timeToLiveInSeconds = 0L, timerIntervalInSeconds = 0L, maxItems = 2)
        cache.put("a", listOf("1"))
        cache.put("b", listOf("2"))
        cache.put("c", listOf("3"))
        assertEquals(2, cache.size())
        assertNull(cache.get("a"))
        assertEquals(listOf("2"), cache.get("b"))
        assertEquals(listOf("3"), cache.get("c"))
    }

    @Test
    fun `get refreshes recency and protects entry from LRU eviction`() {
        val cache = Cache(timeToLiveInSeconds = 0L, timerIntervalInSeconds = 0L, maxItems = 2)
        cache.put("a", listOf("1"))
        cache.put("b", listOf("2"))
        // Touch "a" so "b" becomes the least-recently-used entry.
        cache.get("a")
        cache.put("c", listOf("3"))
        assertEquals(listOf("1"), cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(listOf("3"), cache.get("c"))
    }

    @Test
    fun `entries older than TTL are removed by the sweeper`() {
        val cache = Cache(timeToLiveInSeconds = 1L, timerIntervalInSeconds = 1L, maxItems = 16)
        cache.put("k", listOf("v"))
        assertNotNull(cache.get("k"))
        // Wait for at least one sweeper interval past the TTL.
        Thread.sleep(2_500L)
        assertNull(cache.get("k"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `concurrent puts and gets do not corrupt the cache`() {
        val cache = Cache(timeToLiveInSeconds = 0L, timerIntervalInSeconds = 0L, maxItems = 1024)
        val threads = 8
        val opsPerThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) { t ->
            pool.submit {
                try {
                    start.await()
                    repeat(opsPerThread) { i ->
                        val key = "t$t-$i"
                        cache.put(key, listOf("v$i"))
                        val got = cache.get(key)
                        if (got != null && got != listOf("v$i")) errors.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS), "concurrent ops timed out")
        pool.shutdownNow()
        assertEquals(0, errors.get(), "no thread observed a corrupted value")
        assertTrue(cache.size() in 0..1024)
    }

    private fun newCacheNoSweeper(): Cache =
        // ttl=0 and interval=0 disable the background sweep thread; deterministic for unit tests.
        Cache(timeToLiveInSeconds = 0L, timerIntervalInSeconds = 0L, maxItems = 16)
}
