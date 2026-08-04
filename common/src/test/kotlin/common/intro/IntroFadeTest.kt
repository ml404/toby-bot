package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroFadeTest {

    // --- choosing the ramps -------------------------------------------------

    @Test
    fun `a normal clip gets both ramps at full length`() {
        val ramps = IntroFade.rampsFor(10_000)

        assertEquals(IntroFade.FADE_IN_MS, ramps.fadeInMs)
        assertEquals(IntroFade.FADE_OUT_MS, ramps.fadeOutMs)
    }

    @Test
    fun `without a known length only the fade in survives`() {
        // Music resuming after an intro: there is no end to aim at, and it may
        // well be preempted again before it reaches one.
        val ramps = IntroFade.rampsFor(null)

        assertEquals(IntroFade.FADE_IN_MS, ramps.fadeInMs)
        assertEquals(0, ramps.fadeOutMs)
    }

    @Test
    fun `a stinger is left alone`() {
        // An 800ms airhorn is supposed to be abrupt; 650ms of ramp on it would
        // be most of the airhorn.
        assertTrue(IntroFade.rampsFor(800).isNoOp)
    }

    @Test
    fun `a short clip gets proportionally smaller ramps rather than none`() {
        val ramps = IntroFade.rampsFor(1_500)

        assertTrue(ramps.fadeInMs > 0, "expected a fade in, got ${ramps.fadeInMs}")
        assertTrue(ramps.fadeOutMs > 0, "expected a fade out, got ${ramps.fadeOutMs}")
        assertTrue(
            ramps.fadeInMs + ramps.fadeOutMs <= 1_500 * IntroFade.MAX_FADE_FRACTION,
            "ramps ate more than the budget: $ramps",
        )
        // Scaled down, not truncated to the default.
        assertTrue(ramps.fadeOutMs < IntroFade.FADE_OUT_MS)
    }

    @Test
    fun `the two ends keep their relative sizes when scaled`() {
        val ramps = IntroFade.rampsFor(1_400)

        assertTrue(ramps.fadeOutMs > ramps.fadeInMs, "the fade out should stay the longer of the two: $ramps")
    }

    @Test
    fun `a zero-length playback is treated as unknown rather than dividing by it`() {
        assertEquals(0, IntroFade.rampsFor(0).fadeOutMs)
    }

    // --- the curve ----------------------------------------------------------

    private val ramps = IntroFade.Ramps(fadeInMs = 200, fadeOutMs = 400)
    private val playback = 5_000L

    @Test
    fun `an intro starts silent and reaches full volume at the end of the ramp`() {
        assertEquals(0f, IntroFade.gainAt(0, playback, ramps))
        assertEquals(1f, IntroFade.gainAt(200, playback, ramps))
    }

    @Test
    fun `the middle of a clip is untouched`() {
        assertEquals(1f, IntroFade.gainAt(2_500, playback, ramps))
    }

    @Test
    fun `an intro reaches silence exactly as its clip ends`() {
        // This is the click the fade exists to remove: the clip end marker
        // stops the track at whatever sample it lands on.
        assertEquals(1f, IntroFade.gainAt(playback - 400, playback, ramps))
        assertEquals(0f, IntroFade.gainAt(playback, playback, ramps))
        assertEquals(0f, IntroFade.gainAt(playback + 500, playback, ramps))
    }

    @Test
    fun `the ramp only ever rises across the fade in`() {
        val gains = (0..200 step 20).map { IntroFade.gainAt(it.toLong(), playback, ramps) }

        gains.zipWithNext { a, b -> assertTrue(b >= a, "gain went backwards: $gains") }
        assertTrue(gains.first() < gains.last())
    }

    @Test
    fun `the curve is smooth rather than a straight line`() {
        // A raised cosine passes through the halfway point at half gain like a
        // linear ramp does, but arrives at both ends with zero slope — which
        // is the corner a linear fade would leave behind.
        val half = IntroFade.gainAt(100, playback, ramps)
        assertEquals(0.5f, half, 0.01f)

        val nearStart = IntroFade.gainAt(10, playback, ramps)
        assertTrue(nearStart < 0.05f, "expected a gentle start, got $nearStart")
    }

    @Test
    fun `no ramps means no attenuation anywhere`() {
        assertEquals(1f, IntroFade.gainAt(0, playback, IntroFade.NONE))
        assertEquals(1f, IntroFade.gainAt(playback, playback, IntroFade.NONE))
    }

    @Test
    fun `an unknown length never fades out`() {
        val fadeInOnly = IntroFade.Ramps(fadeInMs = 400, fadeOutMs = 0)

        assertEquals(1f, IntroFade.gainAt(60_000, null, fadeInOnly))
    }

    @Test
    fun `a negative elapsed time is treated as the very start`() {
        assertEquals(0f, IntroFade.gainAt(-500, playback, ramps))
    }

    @Test
    fun `overlapping ramps take the quieter of the two rather than exceeding one`() {
        // rampsFor won't produce these, but a caller supplying its own could.
        val overlapping = IntroFade.Ramps(fadeInMs = 800, fadeOutMs = 800)

        val gain = IntroFade.gainAt(500, 1_000, overlapping)
        assertTrue(gain in 0f..1f, "gain escaped its range: $gain")
    }
}
