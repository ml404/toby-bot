package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroLoudnessFilterFactoryTest {

    private val format: AudioDataFormat = mockk(relaxed = true)
    private val output: UniversalPcmAudioFilter = mockk(relaxed = true)

    @Test
    fun `regular music playback gets no filter at all`() {
        // The factory is installed on the guild player, so it sees every
        // queued track. Measuring an hour of music would be work on the audio
        // thread for a number nothing reads.
        val factory = IntroLoudnessFilterFactory(introIdOf = { null }, onMeasured = { _, _ -> })

        val chain = factory.buildChain(mockk(relaxed = true), format, output)

        assertTrue(chain.isEmpty(), "expected an empty chain for a non-intro track")
    }

    @Test
    fun `an intro track gets a single meter that feeds the rest of the pipeline`() {
        val factory = IntroLoudnessFilterFactory(introIdOf = { "1_2_1" }, onMeasured = { _, _ -> })

        val chain = factory.buildChain(mockk(relaxed = true), format, output)

        // One filter: with a single element there is no ambiguity about which
        // end of lavaplayer's chain forwards to `output`.
        assertEquals(1, chain.size)
        assertInstanceOf(IntroLoudnessMeter::class.java, chain.single())
    }

    @Test
    fun `the measurement is reported against the intro that was playing`() {
        val reported = mutableListOf<Pair<String, Double>>()
        val factory = IntroLoudnessFilterFactory(
            introIdOf = { "42_7_2" },
            onMeasured = { id, rms -> reported += id to rms },
        )
        val meter = factory.buildChain(mockk(relaxed = true), format, output).single() as IntroLoudnessMeter

        meter.process(Array(1) { FloatArray(50) { 0.4f } }, 0, 50)
        meter.close()

        assertEquals(1, reported.size)
        assertEquals("42_7_2", reported.single().first)
    }

    @Test
    fun `a failure recording the measurement never reaches the audio thread`() {
        // close() runs inside lavaplayer's pipeline teardown. A throw here
        // would surface as broken playback in exchange for a statistic.
        val factory = IntroLoudnessFilterFactory(
            introIdOf = { "1_1_1" },
            onMeasured = { _, _ -> error("database is on fire") },
        )
        val meter = factory.buildChain(mockk(relaxed = true), format, output).single() as IntroLoudnessMeter

        meter.process(Array(1) { FloatArray(10) { 0.5f } }, 0, 10)
        meter.close() // must not throw
    }

    @Test
    fun `a failure resolving the intro id degrades to no filter`() {
        val factory = IntroLoudnessFilterFactory(
            introIdOf = { throw IllegalStateException("scheduler state gone") },
            onMeasured = { _, _ -> },
        )

        val chain: List<Any> = factory.buildChain(mockk<AudioTrack>(relaxed = true), format, output)

        assertTrue(chain.isEmpty())
    }
}
