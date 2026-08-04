package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IntroCaptureTest {

    private val fifteenSeconds = IntroClip.MAX_CLIP_DURATION_MS.toLong()

    @Test
    fun `the default window looks backwards from the playhead`() {
        // The whole point: you run the command *after* hearing the bit you
        // liked, so the fifteen seconds in front of the playhead are the wrong
        // fifteen seconds.
        val window = IntroCapture.windowFor(positionMs = 100_000, durationMs = 240_000)

        assertEquals(85_000, window!!.startMs)
        assertEquals(100_000, window.endMs)
    }

    @Test
    fun `early in a track the window runs forwards from the beginning instead`() {
        // Two seconds in there is nothing behind the playhead to capture, and
        // "the opening" is obviously what was meant.
        val window = IntroCapture.windowFor(positionMs = 2_000, durationMs = 240_000)

        assertEquals(0, window!!.startMs)
        assertEquals(fifteenSeconds.toInt(), window.endMs)
    }

    @Test
    fun `a track shorter than the capture length is taken whole`() {
        val window = IntroCapture.windowFor(positionMs = 1_000, durationMs = 6_000)

        assertEquals(0, window!!.startMs)
        assertEquals(6_000, window.endMs)
    }

    @Test
    fun `a playhead less than the capture length in is clamped at zero`() {
        val window = IntroCapture.windowFor(positionMs = 8_000, durationMs = 240_000)

        assertEquals(0, window!!.startMs)
        assertEquals(8_000, window.endMs)
    }

    @Test
    fun `a playhead past the end of the source is pulled back to it`() {
        val window = IntroCapture.windowFor(positionMs = 999_000, durationMs = 60_000)

        assertEquals(45_000, window!!.startMs)
        assertEquals(60_000, window.endMs)
    }

    @Test
    fun `an unknown duration still yields a window`() {
        val window = IntroCapture.windowFor(positionMs = 100_000, durationMs = null)

        assertEquals(85_000, window!!.startMs)
        assertEquals(100_000, window.endMs)
    }

    @Test
    fun `a live stream's sentinel duration is treated as unknown`() {
        // Lavaplayer reports Long.MAX_VALUE for a stream; used as a real
        // length it would sail through every clamp below.
        val window = IntroCapture.windowFor(positionMs = 100_000, durationMs = Long.MAX_VALUE)

        assertEquals(85_000, window!!.startMs)
        assertEquals(100_000, window.endMs)
    }

    @Test
    fun `a track that has not started yet has nothing to capture`() {
        assertNull(IntroCapture.windowFor(positionMs = 0, durationMs = 0))
    }

    @Test
    fun `a negative position is treated as the start of the track`() {
        val window = IntroCapture.windowFor(positionMs = -5_000, durationMs = 60_000)

        assertEquals(0, window!!.startMs)
        assertEquals(fifteenSeconds.toInt(), window.endMs)
    }

    @Test
    fun `a shorter capture length is honoured`() {
        val window = IntroCapture.windowFor(positionMs = 100_000, durationMs = 240_000, lengthMs = 5_000)

        assertEquals(95_000, window!!.startMs)
        assertEquals(100_000, window.endMs)
    }

    @Test
    fun `a capture length of nothing produces no window`() {
        assertNull(IntroCapture.windowFor(positionMs = 100_000, durationMs = 240_000, lengthMs = 0))
    }

    @Test
    fun `the window always fits the clip rules it will be validated against`() {
        val window = IntroCapture.windowFor(positionMs = 500_000, durationMs = 900_000)!!

        assertNull(IntroClip.validate(window.startMs, window.endMs, 900_000))
    }
}
