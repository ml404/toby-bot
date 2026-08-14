package common.helpers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class TtlCacheTest {

    private var now = 0L
    private fun cache(maxEntries: Int = 4, ttl: Duration = Duration.ofMinutes(10)) =
        TtlCache<String, String>(maxEntries, ttl) { now }

    @Test
    fun `stores and returns a value`() {
        val cache = cache()
        cache["set:vow"] = "cards"
        assertEquals("cards", cache["set:vow"])
    }

    @Test
    fun `an unknown key misses`() {
        assertNull(cache()["nothing"])
    }

    @Test
    fun `an entry past its ttl misses and is dropped`() {
        val cache = cache(ttl = Duration.ofMinutes(10))
        cache["set:vow"] = "cards"

        now += Duration.ofMinutes(10).toNanos()

        assertNull(cache["set:vow"])
        assertEquals(0, cache.size) { "a stale entry shouldn't keep holding its cards" }
    }

    @Test
    fun `an entry inside its ttl still hits`() {
        val cache = cache(ttl = Duration.ofMinutes(10))
        cache["set:vow"] = "cards"

        now += Duration.ofMinutes(9).toNanos()

        assertEquals("cards", cache["set:vow"])
    }

    @Test
    fun `the cache is bounded`() {
        val cache = cache(maxEntries = 4)
        repeat(10) { cache["q$it"] = "cards" }
        assertTrue(cache.size <= 4) { "expected at most 4 entries, had ${cache.size}" }
    }

    @Test
    fun `getOrPut only calls the supplier on a miss`() {
        val cache = cache()
        var calls = 0
        val supplier = { calls++; "cards" }

        assertEquals("cards", cache.getOrPut("set:vow", supplier))
        assertEquals("cards", cache.getOrPut("set:vow", supplier))

        assertEquals(1, calls)
    }

    @Test
    fun `getOrPut doesn't cache a null result`() {
        val cache = cache()
        assertNull(cache.getOrPut("set:vow") { null })
        assertEquals(0, cache.size) { "a failed lookup must not be remembered as an answer" }
    }
}
