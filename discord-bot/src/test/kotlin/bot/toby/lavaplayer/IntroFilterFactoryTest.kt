package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroFilterFactoryTest {

    private val format = Pcm16AudioDataFormat(2, 48_000, 960, true)
    private val output: UniversalPcmAudioFilter = mockk(relaxed = true)

    /** Stand-in for [TrackScheduler]'s view of what a track is. */
    private class FakeContext(
        val introId: String? = null,
        val playbackMs: Long? = null,
        val resuming: Boolean = false,
        val introIdThrows: Boolean = false,
        val playbackThrows: Boolean = false,
    ) : IntroTrackContext {
        override fun introIdFor(track: AudioTrack): String? =
            if (introIdThrows) throw IllegalStateException("scheduler state gone") else introId

        override fun introPlaybackMsFor(track: AudioTrack): Long? =
            if (playbackThrows) throw IllegalStateException("no bookkeeping") else playbackMs

        override fun isResumingTrack(track: AudioTrack): Boolean = resuming
    }

    private fun chainFor(context: IntroTrackContext, onMeasured: (String, Double) -> Unit = { _, _ -> }) =
        IntroFilterFactory(context, onMeasured).buildChain(mockk<AudioTrack>(relaxed = true), format, output)

    @Test
    fun `regular music playback gets no filter at all`() {
        // The factory is installed on the guild player, so it sees every
        // queued track. Measuring or ramping an hour of music would be work on
        // the audio thread that nothing asked for.
        assertTrue(chainFor(FakeContext()).isEmpty(), "expected an empty chain for a non-intro track")
    }

    @Test
    fun `an intro is metered first and faded second`() {
        // Order is the whole point: the meter has to see the track's own
        // level. Behind the fade it would measure the ramp too and report
        // every intro as quieter than it is — which the loudness correction
        // would then obligingly "fix" on the next play.
        val chain = chainFor(FakeContext(introId = "1_2_1", playbackMs = 10_000))

        assertEquals(2, chain.size)
        assertInstanceOf(IntroLoudnessMeter::class.java, chain.first())
        assertInstanceOf(IntroFadeFilter::class.java, chain.last())
    }

    @Test
    fun `music resuming after an intro is faded back in without being metered`() {
        // Its loudness is nobody's business — it isn't an intro, it's the
        // track the intro interrupted.
        val chain = chainFor(FakeContext(resuming = true))

        assertEquals(1, chain.size)
        assertInstanceOf(IntroFadeFilter::class.java, chain.single())
    }

    @Test
    fun `an intro wins over the resume mark when a track somehow carries both`() {
        val chain = chainFor(FakeContext(introId = "1_2_1", playbackMs = 5_000, resuming = true))

        assertEquals(2, chain.size)
        assertInstanceOf(IntroLoudnessMeter::class.java, chain.first())
    }

    @Test
    fun `the measurement is reported against the intro that was playing`() {
        val reported = mutableListOf<Pair<String, Double>>()
        val chain = chainFor(FakeContext(introId = "42_7_2", playbackMs = 8_000)) { id, rms -> reported += id to rms }
        val meter = chain.first() as IntroLoudnessMeter

        meter.process(Array(1) { FloatArray(50) { 0.4f } }, 0, 50)
        meter.close()

        assertEquals(1, reported.size)
        assertEquals("42_7_2", reported.single().first)
    }

    @Test
    fun `a failure recording the measurement never reaches the audio thread`() {
        // close() runs inside lavaplayer's pipeline teardown. A throw here
        // would surface as broken playback in exchange for a statistic.
        val chain = chainFor(FakeContext(introId = "1_1_1", playbackMs = 6_000)) { _, _ ->
            error("database is on fire")
        }
        val meter = chain.first() as IntroLoudnessMeter

        meter.process(Array(1) { FloatArray(10) { 0.5f } }, 0, 10)
        meter.close() // must not throw
    }

    @Test
    fun `a failure resolving the intro id degrades to no filter`() {
        assertTrue(chainFor(FakeContext(introIdThrows = true)).isEmpty())
    }

    @Test
    fun `a failure resolving the playback length still gets an intro chain`() {
        // Without a length there is nothing to fade *out* towards, but the
        // fade in and the measurement are both still worth having.
        val chain = chainFor(FakeContext(introId = "1_1_1", playbackThrows = true))

        assertEquals(2, chain.size)
        assertInstanceOf(IntroFadeFilter::class.java, chain.last())
    }
}
