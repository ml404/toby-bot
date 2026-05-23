package common.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Coverage for the jackpot payout wheel — CSV parsing, weight-respecting
 * spin distribution, and the validator used by the moderation save path.
 * The live reader is wired into [database.service.JackpotHelper.rollOnWin];
 * see [database.service.JackpotHelperTest] for the integration test.
 */
class JackpotWheelTest {

    @Test
    fun `parse handles the default segment string`() {
        val segments = JackpotWheel.parse(JackpotWheel.DEFAULT_SEGMENTS)
        assertEquals(5, segments.size)
        assertEquals(JackpotWheel.Segment(80, 0.01), segments[0])
        assertEquals(JackpotWheel.Segment(10, 0.05), segments[1])
        assertEquals(JackpotWheel.Segment(5, 0.10), segments[2])
        assertEquals(JackpotWheel.Segment(4, 0.20), segments[3])
        assertEquals(JackpotWheel.Segment(1, 0.50), segments[4])
    }

    @Test
    fun `parse falls back to defaults on null and blank`() {
        val expected = JackpotWheel.parse(JackpotWheel.DEFAULT_SEGMENTS)
        assertEquals(expected, JackpotWheel.parse(null))
        assertEquals(expected, JackpotWheel.parse(""))
        assertEquals(expected, JackpotWheel.parse("   "))
    }

    @Test
    fun `parse falls back to defaults on malformed CSV`() {
        val expected = JackpotWheel.parse(JackpotWheel.DEFAULT_SEGMENTS)
        // Non-numeric weight, missing colon, out-of-range pct, zero weight,
        // and over-MAX segment counts each fall back rather than throwing
        // — the live reader never gets to crash the casino path.
        listOf(
            "abc",
            "5,3,2",
            "10:200",
            "0:50",
            "-1:50",
            "10:0",
            (1..(JackpotWheel.MAX_SEGMENTS + 1)).joinToString(",") { "1:1" },
        ).forEach { bad ->
            assertEquals(expected, JackpotWheel.parse(bad), "expected fallback for `$bad`")
        }
    }

    @Test
    fun `parse accepts a single fixed-pct segment`() {
        // Equivalent of the deleted JACKPOT_PAYOUT_PCT=30 setting.
        val segments = JackpotWheel.parse("1:30")
        assertEquals(1, segments.size)
        assertEquals(JackpotWheel.Segment(1, 0.30), segments[0])
    }

    @Test
    fun `spin with seeded RNG is reproducible`() {
        val segments = JackpotWheel.parse(JackpotWheel.DEFAULT_SEGMENTS)
        val a = JackpotWheel.spin(segments, Random(42))
        val b = JackpotWheel.spin(segments, Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `spin distribution converges to the configured weights`() {
        val segments = JackpotWheel.parse(JackpotWheel.DEFAULT_SEGMENTS)
        val totalWeight = segments.sumOf { it.weight }.toDouble()
        val rng = Random(0xCAFE)
        val iterations = 200_000
        val counts = IntArray(segments.size)
        repeat(iterations) {
            counts[JackpotWheel.spin(segments, rng).tierIndex]++
        }
        // Each segment should land within 0.7 percentage points of its
        // configured share — generous enough to absorb sampling noise
        // at 200k iterations, tight enough to catch a logic bug that
        // ignores the weights.
        segments.forEachIndexed { i, seg ->
            val expected = seg.weight / totalWeight
            val actual = counts[i] / iterations.toDouble()
            assertTrue(
                kotlin.math.abs(expected - actual) < 0.007,
                "segment $i — expected ≈ $expected, observed $actual"
            )
        }
    }

    @Test
    fun `single-tier wheel always picks index 0`() {
        val segments = JackpotWheel.parse("1:30")
        repeat(1_000) {
            val spin = JackpotWheel.spin(segments, Random(it.toLong()))
            assertEquals(0, spin.tierIndex)
            assertEquals(0.30, spin.payoutPct, 1e-9)
        }
    }

    @Test
    fun `validateConfigString accepts the default and single-tier configs`() {
        assertNull(JackpotWheel.validateConfigString(null))
        assertNull(JackpotWheel.validateConfigString(""))
        assertNull(JackpotWheel.validateConfigString("   "))
        assertNull(JackpotWheel.validateConfigString(JackpotWheel.DEFAULT_SEGMENTS))
        assertNull(JackpotWheel.validateConfigString("1:30"))
        assertNull(JackpotWheel.validateConfigString("50:1, 50:50"))
    }

    @Test
    fun `validateConfigString rejects bad weights, pcts, and oversized inputs`() {
        // Each branch returns a human-readable error string the
        // moderation UI surfaces verbatim — no exception leak.
        assertNotNull(JackpotWheel.validateConfigString("abc"))
        assertNotNull(JackpotWheel.validateConfigString("5,3,2"))
        assertNotNull(JackpotWheel.validateConfigString("0:50"))
        assertNotNull(JackpotWheel.validateConfigString("-1:50"))
        assertNotNull(JackpotWheel.validateConfigString("10:0"))
        assertNotNull(JackpotWheel.validateConfigString("10:101"))
        val oversize = (1..(JackpotWheel.MAX_SEGMENTS + 1)).joinToString(",") { "1:1" }
        assertNotNull(JackpotWheel.validateConfigString(oversize))
    }
}
