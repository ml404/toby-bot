package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import common.intro.IntroFade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroFadeFilterTest {

    private val sampleRate = 48_000

    /**
     * Records what actually came out the other side. Clamps its own read the
     * way lavaplayer's real filters do — the buffer is forwarded verbatim, so
     * an over-long `length` is the caller's problem to survive, not a reason
     * for the filter under test to have rewritten it.
     */
    private class Sink : FloatPcmAudioFilter {
        val received = mutableListOf<FloatArray>()
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            received += input[0].copyOfRange(offset, minOf(offset + length, input[0].size))
        }

        override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    /** One channel of full-scale samples, [ms] milliseconds long. */
    private fun buffer(ms: Int) = Array(1) { FloatArray(sampleRate * ms / 1000) { 1f } }

    private fun filter(
        sink: Sink,
        playbackMs: Long? = 5_000,
        ramps: IntroFade.Ramps = IntroFade.rampsFor(5_000),
    ) = IntroFadeFilter(sink, sampleRate, playbackMs, ramps)

    private fun push(f: IntroFadeFilter, ms: Int) {
        val buf = buffer(ms)
        f.process(buf, 0, buf[0].size)
    }

    @Test
    fun `the first samples of an intro are near silent`() {
        val sink = Sink()
        val f = filter(sink)

        push(f, 20)

        val out = sink.received.single()
        assertTrue(out.first() < 0.001f, "the very first sample should be silent, was ${out.first()}")
        assertTrue(out.last() < 0.2f, "20ms into a 250ms fade should still be quiet, was ${out.last()}")
    }

    @Test
    fun `the ramp rises smoothly within a single buffer rather than stepping`() {
        // Holding one gain for a whole 20ms buffer would turn the ramp into a
        // staircase, which is its own kind of audible.
        val sink = Sink()
        val f = filter(sink)

        push(f, 20)

        val out = sink.received.single()
        out.toList().zipWithNext { a, b -> assertTrue(b >= a, "gain went backwards inside a buffer") }
        assertTrue(out.distinct().size > 100, "expected a continuous ramp, saw ${out.distinct().size} levels")
    }

    @Test
    fun `audio in the middle of a clip is passed through untouched`() {
        val sink = Sink()
        val f = filter(sink)

        push(f, 1_000) // past the fade in
        sink.received.clear()
        push(f, 20)

        assertTrue(sink.received.single().all { it == 1f }, "mid-clip audio was altered")
    }

    @Test
    fun `an intro is silent by the time its clip end arrives`() {
        // The click this exists to remove: the clip end marker stops playback
        // at whatever sample it lands on.
        val sink = Sink()
        val f = filter(sink)

        push(f, 4_980)
        sink.received.clear()
        push(f, 20)

        val out = sink.received.single()
        assertTrue(out.last() < 0.01f, "expected near-silence at the clip end, was ${out.last()}")
    }

    @Test
    fun `every channel is faded, not just the first`() {
        val sink = object : FloatPcmAudioFilter {
            lateinit var seen: Array<FloatArray>
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                seen = Array(input.size) { input[it].copyOf() }
            }

            override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        val f = IntroFadeFilter(sink, sampleRate, 5_000, IntroFade.rampsFor(5_000))

        val stereo = Array(2) { FloatArray(480) { 1f } }
        f.process(stereo, 0, 480)

        assertEquals(sink.seen[0].toList(), sink.seen[1].toList(), "the channels drifted apart")
        assertTrue(sink.seen[1].first() < 0.001f, "the right channel was not faded")
    }

    @Test
    fun `a seek restarts the fade, so a clipped intro ramps from its clip start`() {
        // The clip start is applied as a seek. Without this a clip beginning
        // at 1:00 would count as a minute elapsed before its first sample and
        // skip its fade in entirely.
        val sink = Sink()
        val f = filter(sink)

        push(f, 1_000)
        f.seekPerformed(60_000, 60_000)
        sink.received.clear()
        push(f, 20)

        assertTrue(sink.received.single().first() < 0.001f, "the fade did not restart after the seek")
    }

    @Test
    fun `music resuming after an intro fades in and then stays put`() {
        val sink = Sink()
        val f = IntroFadeFilter(
            sink, sampleRate, null,
            IntroFade.rampsFor(null, fadeInMs = IntroFade.RESUME_FADE_IN_MS, fadeOutMs = 0),
        )

        push(f, 20)
        assertTrue(sink.received.single().first() < 0.001f)

        // Minutes later it is still at full volume: there is no end to fade
        // towards, and inventing one would duck the music for no reason.
        sink.received.clear()
        push(f, 1_000)
        push(f, 1_000)
        sink.received.clear()
        push(f, 20)
        assertTrue(sink.received.single().all { it == 1f })
    }

    @Test
    fun `a stinger too short to fade is passed through completely untouched`() {
        val sink = Sink()
        val f = filter(sink, playbackMs = 800, ramps = IntroFade.rampsFor(800))

        push(f, 20)

        assertTrue(sink.received.single().all { it == 1f }, "a sub-second intro should not be ramped")
    }

    @Test
    fun `an empty buffer is forwarded without advancing the ramp`() {
        val sink = Sink()
        val f = filter(sink)

        f.process(buffer(20), 0, 0)
        push(f, 20)

        // The zero-length call must not have consumed any of the fade.
        assertTrue(sink.received.last().first() < 0.001f)
    }

    @Test
    fun `a buffer shorter than its declared length does not overrun`() {
        // Defensive: an ArrayIndexOutOfBounds here would be thrown from the
        // audio thread and take the whole track down, not just the fade.
        val sink = Sink()
        val f = filter(sink)

        val short = Array(1) { FloatArray(100) { 1f } }
        f.process(short, 0, 480) // claims more than the array holds
    }

    @Test
    fun `an unusable sample rate disables the fade instead of dividing by it`() {
        val sink = Sink()
        val f = IntroFadeFilter(sink, 0, 5_000, IntroFade.rampsFor(5_000))

        val buf = Array(1) { FloatArray(480) { 1f } }
        f.process(buf, 0, 480)

        assertTrue(sink.received.single().all { it == 1f })
    }
}
