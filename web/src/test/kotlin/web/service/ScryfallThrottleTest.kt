package web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class ScryfallThrottleTest {

    /**
     * A hand-cranked clock. [advanceOnSleep] mirrors one caller making
     * several calls in sequence (its own sleeping moves time on); leaving it
     * off mirrors several callers arriving at once, none of whom is helped by
     * another's wait.
     */
    private class FakeTime(private val advanceOnSleep: Boolean = true) {
        var nanos = 0L
        val slept = mutableListOf<Long>()
        fun sleep(millis: Long) {
            slept += millis
            if (advanceOnSleep) nanos += millis * 1_000_000
        }
    }

    private fun throttle(
        time: FakeTime,
        minInterval: Duration = Duration.ofMillis(100),
        maxWait: Duration = Duration.ofSeconds(3),
    ) = ScryfallThrottle(minInterval, maxWait, { time.nanos }, time::sleep)

    @Test
    fun `the first call goes straight out`() {
        val time = FakeTime()
        assertTrue(throttle(time).awaitSlot())
        assertTrue(time.slept.isEmpty())
    }

    @Test
    fun `back-to-back calls are spaced by the minimum interval`() {
        val time = FakeTime()
        val throttle = throttle(time)

        repeat(4) { assertTrue(throttle.awaitSlot()) }

        // The first is free; each of the next three waits out the interval.
        assertEquals(listOf(100L, 100L, 100L), time.slept)
    }

    @Test
    fun `a caller that already waited long enough is not slowed down again`() {
        val time = FakeTime()
        val throttle = throttle(time)
        assertTrue(throttle.awaitSlot())

        time.nanos += Duration.ofSeconds(1).toNanos() // a slow page fetch

        assertTrue(throttle.awaitSlot())
        assertTrue(time.slept.isEmpty()) { "the interval had already elapsed" }
    }

    @Test
    fun `concurrent callers are staggered rather than all waiting the same time`() {
        val time = FakeTime(advanceOnSleep = false)
        val throttle = throttle(time, Duration.ofMillis(10), Duration.ofSeconds(3))

        repeat(4) { assertTrue(throttle.awaitSlot()) }

        // Each arrival claims the next free slot instead of piling onto one.
        assertEquals(listOf(10L, 20L, 30L), time.slept)
    }

    @Test
    fun `a call that would queue past the cap is refused rather than parked`() {
        val time = FakeTime(advanceOnSleep = false)
        // 10ms apart, refusing anything that would wait more than 50ms: the
        // seventh caller in a simultaneous burst is over the line.
        val throttle = throttle(time, Duration.ofMillis(10), Duration.ofMillis(50))

        val outcomes = (1..8).map { throttle.awaitSlot() }

        assertEquals(listOf(true, true, true, true, true, true, false, false), outcomes)
    }

    @Test
    fun `a refusal doesn't claim a slot, so the queue drains as time passes`() {
        val time = FakeTime(advanceOnSleep = false)
        val throttle = throttle(time, Duration.ofMillis(10), Duration.ofMillis(20))
        repeat(3) { assertTrue(throttle.awaitSlot()) } // booked out to +30ms
        assertFalse(throttle.awaitSlot())

        time.nanos += Duration.ofMillis(30).toNanos()

        assertTrue(throttle.awaitSlot()) { "once the backlog clears, callers get through again" }
    }
}
