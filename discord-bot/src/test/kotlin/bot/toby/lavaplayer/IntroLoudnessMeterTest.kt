package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import common.intro.IntroLoudness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class IntroLoudnessMeterTest {

    /** Records what reached the rest of the pipeline. */
    private class RecordingFilter : FloatPcmAudioFilter {
        val received = mutableListOf<Triple<Array<FloatArray>, Int, Int>>()
        var flushed = 0
        var closed = 0
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            received += Triple(input, offset, length)
        }
        override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
        override fun flush() { flushed++ }
        override fun close() { closed++ }
    }

    private fun constantFrame(amplitude: Float, samples: Int, channels: Int = 2) =
        Array(channels) { FloatArray(samples) { amplitude } }

    @Test
    fun `every sample is forwarded downstream unchanged`() {
        // The meter must be acoustically invisible. If it ever swallowed or
        // altered audio, every intro in every guild would be affected.
        val downstream = RecordingFilter()
        val meter = IntroLoudnessMeter(downstream) {}
        val frame = constantFrame(0.25f, 8)

        meter.process(frame, 0, 8)

        assertEquals(1, downstream.received.size)
        val (forwarded, offset, length) = downstream.received.single()
        assertTrue(forwarded === frame, "the same buffer should be passed on, not a copy")
        assertEquals(0, offset)
        assertEquals(8, length)
        assertTrue(forwarded.all { channel -> channel.all { it == 0.25f } })
    }

    @Test
    fun `the measured level is the rms of what played`() {
        var measured: Double? = null
        val meter = IntroLoudnessMeter(RecordingFilter()) { measured = it }

        meter.process(constantFrame(0.5f, 100), 0, 100)
        meter.close()

        assertNotNull(measured)
        assertTrue(abs(0.5 - measured!!) < 1e-6, "expected ~0.5, got $measured")
    }

    @Test
    fun `a track that produced no audio reports nothing`() {
        // Reporting 0.0 would look like "extremely quiet" and earn the maximum
        // boost next play; the honest answer is that we never measured it.
        var measured: Double? = null
        val meter = IntroLoudnessMeter(RecordingFilter()) { measured = it }

        meter.close()

        assertNull(measured)
    }

    @Test
    fun `a seek discards everything sampled before the clip started`() {
        // Clip start offsets are applied as a seek. Without the reset, audio
        // the decoder emitted before jumping to the clip would be averaged in.
        var measured: Double? = null
        val meter = IntroLoudnessMeter(RecordingFilter()) { measured = it }

        meter.process(constantFrame(0.9f, 200), 0, 200)
        meter.seekPerformed(0L, 0L)
        meter.process(constantFrame(0.1f, 200), 0, 200)
        meter.close()

        assertTrue(abs(0.1 - measured!!) < 1e-6, "expected only the post-seek audio, got $measured")
    }

    @Test
    fun `only the requested window of the buffer is measured`() {
        var measured: Double? = null
        val meter = IntroLoudnessMeter(RecordingFilter()) { measured = it }
        // Loud padding either side of a quiet window; only the window counts.
        val frame = Array(1) { FloatArray(30) { i -> if (i in 10..19) 0.2f else 1.0f } }

        meter.process(frame, 10, 10)
        meter.close()

        assertTrue(abs(0.2 - measured!!) < 1e-6, "expected ~0.2, got $measured")
    }

    @Test
    fun `a short buffer does not throw on the audio thread`() {
        // An ArrayIndexOutOfBounds raised here would kill the track, not just
        // the measurement, so the accumulator clamps rather than trusting the
        // declared length.
        val downstream = RecordingFilter()
        val meter = IntroLoudnessMeter(downstream) {}

        meter.process(Array(1) { FloatArray(4) { 0.5f } }, 0, 999)

        assertEquals(1, downstream.received.size)
    }

    @Test
    fun `closing twice reports once`() {
        var reports = 0
        val meter = IntroLoudnessMeter(RecordingFilter()) { reports++ }

        meter.process(constantFrame(0.3f, 10), 0, 10)
        meter.close()
        meter.close()

        assertEquals(1, reports)
    }

    @Test
    fun `the measurement is the number the gain calculation consumes`() {
        // Ties the meter to the correction: a track measured well above target
        // must come out asking to be turned down.
        var measured: Double? = null
        val meter = IntroLoudnessMeter(RecordingFilter()) { measured = it }

        meter.process(constantFrame((IntroLoudness.TARGET_RMS * 2).toFloat(), 64), 0, 64)
        meter.close()

        assertTrue(IntroLoudness.gainFor(measured) < 1.0, "gain was ${IntroLoudness.gainFor(measured)}")
    }
}
