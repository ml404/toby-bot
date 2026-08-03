package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class IntroLoudnessTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= tolerance, "expected ~$expected but was $actual")
    }

    // --- measurement --------------------------------------------------------

    @Test
    fun `rms of a constant signal is its amplitude`() {
        // 100 samples of 0.5 → mean square 0.25 → rms 0.5.
        assertClose(0.5, IntroLoudness.rmsOf(sumOfSquares = 0.25 * 100, sampleCount = 100)!!)
    }

    @Test
    fun `no samples means no measurement`() {
        // A track that failed before producing audio must not be recorded as
        // silent — silent would demand the maximum boost next time.
        assertNull(IntroLoudness.rmsOf(sumOfSquares = 0.0, sampleCount = 0))
        assertNull(IntroLoudness.rmsOf(sumOfSquares = 5.0, sampleCount = -1))
    }

    // --- gain ---------------------------------------------------------------

    @Test
    fun `an unmeasured intro is left exactly as it is`() {
        // Every existing row starts null. The first play of anything must
        // sound identical to today or this ships as a surprise volume change.
        assertEquals(1.0, IntroLoudness.gainFor(null))
        assertEquals(90, IntroLoudness.normalisedVolume(90, null))
    }

    @Test
    fun `a track already at target is left alone`() {
        assertClose(1.0, IntroLoudness.gainFor(IntroLoudness.TARGET_RMS))
    }

    @Test
    fun `a quiet track is boosted and a loud one cut`() {
        assertTrue(IntroLoudness.gainFor(IntroLoudness.TARGET_RMS / 1.5) > 1.0)
        assertTrue(IntroLoudness.gainFor(IntroLoudness.TARGET_RMS * 1.5) < 1.0)
    }

    @Test
    fun `correction is clamped in both directions`() {
        // Rescuing a recording 30 dB down just amplifies its noise floor, and
        // an unclamped cut could mute an intro entirely.
        assertEquals(IntroLoudness.MAX_GAIN, IntroLoudness.gainFor(0.0001 + IntroLoudness.MIN_MEASURABLE_RMS))
        assertEquals(IntroLoudness.MIN_GAIN, IntroLoudness.gainFor(0.9))
    }

    @Test
    fun `a measurement below the noise floor is treated as unusable, not as silence`() {
        assertEquals(1.0, IntroLoudness.gainFor(IntroLoudness.MIN_MEASURABLE_RMS / 2))
        assertEquals(1.0, IntroLoudness.gainFor(Double.NaN))
    }

    // --- applied volume -----------------------------------------------------

    @Test
    fun `two tracks at the same nominal volume end up at comparable levels`() {
        // The actual complaint: same number, wildly different loudness.
        val quiet = IntroLoudness.TARGET_RMS / 1.8
        val loud = IntroLoudness.TARGET_RMS * 1.8

        val quietPlayback = IntroLoudness.normalisedVolume(50, quiet)
        val loudPlayback = IntroLoudness.normalisedVolume(50, loud)

        assertTrue(quietPlayback > 50, "quiet source should be boosted, got $quietPlayback")
        assertTrue(loudPlayback < 50, "loud source should be cut, got $loudPlayback")
        // Perceived level = volume x source rms. After correction the two
        // should land far closer together than the 3.24x they started apart.
        val ratio = (quietPlayback * quiet) / (loudPlayback * loud)
        assertTrue(ratio in 0.7..1.4, "levels should be close after correction, ratio was $ratio")
    }

    @Test
    fun `the volume handed to the player stays inside the accepted range`() {
        assertTrue(IntroLoudness.normalisedVolume(200, IntroLoudness.TARGET_RMS / 4) <= 200)
        assertTrue(IntroLoudness.normalisedVolume(0, IntroLoudness.TARGET_RMS / 4) >= 0)
    }

    // --- description --------------------------------------------------------

    @Test
    fun `loudness is described only when it was actually measured`() {
        assertNull(IntroLoudness.describe(null))
        assertNull(IntroLoudness.describe(IntroLoudness.MIN_MEASURABLE_RMS / 2))
        assertEquals("level", IntroLoudness.describe(IntroLoudness.TARGET_RMS))
        assertEquals("loud source", IntroLoudness.describe(IntroLoudness.TARGET_RMS * 2))
        assertEquals("quiet source", IntroLoudness.describe(IntroLoudness.TARGET_RMS / 2))
    }
}
