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

    private val playing: AudioTrack = mockk(relaxed = true)

    /** Stand-in for [TrackScheduler]'s view of what a track is. */
    private inner class FakeContext(
        val introId: String? = null,
        val playbackMs: Long? = null,
        val resuming: Boolean = false,
        val introIdThrows: Boolean = false,
        val playbackThrows: Boolean = false,
        val current: AudioTrack? = playing,
    ) : IntroTrackContext {
        override fun currentTrack(): AudioTrack? = current

        override fun introIdFor(track: AudioTrack): String? =
            if (introIdThrows) throw IllegalStateException("scheduler state gone") else introId

        override fun introPlaybackMsFor(track: AudioTrack): Long? =
            if (playbackThrows) throw IllegalStateException("no bookkeeping") else playbackMs

        override fun isResumingTrack(track: AudioTrack): Boolean = resuming
    }

    /**
     * Built the way lavaplayer actually builds it: with a **null** track.
     *
     * `AudioProcessingContext` carries no track, and `UserProvidedAudioFilters`
     * — the only call site in 2.2.6 — passes a literal null. Every test here
     * used to hand over a real track, which is why a non-null Kotlin parameter
     * that threw on every single track in production passed the whole suite.
     */
    private fun chainFor(context: IntroTrackContext, onMeasured: (String, Double) -> Unit = { _, _ -> }) =
        IntroFilterFactory(context, onMeasured).buildChain(null, format, output)

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
    fun `a null track is resolved from the player rather than thrown on`() {
        // The regression that broke every track the bot played: lavaplayer
        // always passes null here, and a non-null Kotlin parameter turns that
        // into a NullPointerException in the method prologue — before any of
        // this class's own code, so none of its defensiveness applied. It
        // surfaced as "Something went wrong when decoding the track".
        val chain = IntroFilterFactory(FakeContext(introId = "1_2_1", playbackMs = 9_000)) { _, _ -> }
            .buildChain(null, format, output)

        assertEquals(2, chain.size)
    }

    @Test
    fun `an idle player with a null track yields no filters instead of failing`() {
        assertTrue(chainFor(FakeContext(current = null)).isEmpty())
    }

    @Test
    fun `an explicit track is still honoured if lavaplayer ever supplies one`() {
        val chain = IntroFilterFactory(FakeContext(introId = "1_2_1", playbackMs = 9_000)) { _, _ -> }
            .buildChain(mockk<AudioTrack>(relaxed = true), format, output)

        assertEquals(2, chain.size)
    }

    @Test
    fun `a failure resolving the intro id degrades to no filter`() {
        assertTrue(chainFor(FakeContext(introIdThrows = true)).isEmpty())
    }

    @Test
    fun `nothing this class does can take playback down with it`() {
        // It runs on the decode path, so a throw here is a track that won't
        // play. Whatever goes wrong, the answer is no filters and a log line.
        val exploding = object : IntroTrackContext {
            override fun currentTrack(): AudioTrack = error("boom")
            override fun introIdFor(track: AudioTrack) = error("boom")
            override fun introPlaybackMsFor(track: AudioTrack) = error("boom")
            override fun isResumingTrack(track: AudioTrack) = error("boom")
        }

        assertTrue(IntroFilterFactory(exploding) { _, _ -> }.buildChain(null, format, output).isEmpty())
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
