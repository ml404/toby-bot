package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.UserProvidedAudioFilters
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The factory as **lavaplayer** calls it, rather than as we imagined it.
 *
 * Every other test of this class invokes `buildChain` directly, which is a
 * call that never happens in production — and getting its arguments wrong is
 * precisely how a `NullPointerException` on every track the bot played got
 * through a green suite, including a test written specifically to prove the
 * wiring was real.
 *
 * So these drive the library's own construction path instead:
 * `UserProvidedAudioFilters` is what actually asks for the chain, and it is
 * built here for real. Nothing in this file asserts anything about our
 * behaviour that the other tests don't already cover; what it pins down is the
 * *contract* — what lavaplayer hands over, and that we survive it.
 */
class IntroFilterFactoryLavaplayerContractTest {

    private val format = Pcm16AudioDataFormat(2, 48_000, 960, true)

    /** The context lavaplayer reads the factory and output format out of. */
    private fun contextWith(factory: PcmFilterFactory): AudioProcessingContext {
        val options = AudioPlayerOptions()
        options.filterFactory.set(factory)
        // A real configuration: the context constructor reads its hot-swap
        // flag. The frame buffer is untouched while the chain is built.
        return AudioProcessingContext(AudioConfiguration(), null, options, format)
    }

    /** Builds the chain the way the library does, and returns nothing useful. */
    private fun buildThroughLavaplayer(factory: PcmFilterFactory) {
        UserProvidedAudioFilters(contextWith(factory), mockk<UniversalPcmAudioFilter>(relaxed = true))
    }

    private class RecordingContext(private val introId: String?) : IntroTrackContext {
        val playing: AudioTrack = mockk(relaxed = true)
        var askedForCurrentTrack = false

        override fun currentTrack(): AudioTrack {
            askedForCurrentTrack = true
            return playing
        }

        override fun introIdFor(track: AudioTrack): String? = introId
        override fun introPlaybackMsFor(track: AudioTrack): Long = 9_000
        override fun isResumingTrack(track: AudioTrack): Boolean = false
    }

    @Test
    fun `lavaplayer asks for the chain without saying which track it is for`() {
        // The fact the whole outage turned on. If a future lavaplayer starts
        // passing a real track, this is where we find out — and the fallback
        // in the factory quietly stops being load-bearing.
        var called = false
        var handedTrack: AudioTrack? = mockk(relaxed = true)

        buildThroughLavaplayer { track, _, _ ->
            called = true
            handedTrack = track
            emptyList()
        }

        assertTrue(called, "lavaplayer should have asked the factory for a chain")
        assertNull(handedTrack, "lavaplayer passes null for the track; the factory must cope with that")
    }

    @Test
    fun `the real factory survives being constructed by lavaplayer`() {
        // The regression itself: a non-null Kotlin parameter made this throw
        // in the method prologue, failing pipeline construction — and so
        // playback — for every track.
        buildThroughLavaplayer(IntroFilterFactory(RecordingContext("1_2_1")) { _, _ -> })
    }

    @Test
    fun `it falls back to the playing track, so the chain is really installed`() {
        // Surviving the call isn't enough — an implementation that returned
        // an empty chain for everything would also "survive", and silently do
        // nothing for ever.
        val context = RecordingContext("1_2_1")

        buildThroughLavaplayer(IntroFilterFactory(context) { _, _ -> })

        assertTrue(context.askedForCurrentTrack, "the factory should resolve the track from the player")
    }

    @Test
    fun `ordinary playback still builds through cleanly with no intro filters`() {
        val context = RecordingContext(introId = null)

        buildThroughLavaplayer(IntroFilterFactory(context) { _, _ -> })

        assertTrue(context.askedForCurrentTrack)
    }

    @Test
    fun `an idle player builds through cleanly too`() {
        val idle = object : IntroTrackContext {
            override fun currentTrack(): AudioTrack? = null
            override fun introIdFor(track: AudioTrack): String? = null
            override fun introPlaybackMsFor(track: AudioTrack): Long? = null
            override fun isResumingTrack(track: AudioTrack): Boolean = false
        }

        buildThroughLavaplayer(IntroFilterFactory(idle) { _, _ -> })
    }

    @Test
    fun `a factory that throws internally does not take pipeline construction with it`() {
        // It sits on the decode path: whatever goes wrong in here, the track
        // still has to play.
        val exploding = object : IntroTrackContext {
            override fun currentTrack(): AudioTrack = error("scheduler is gone")
            override fun introIdFor(track: AudioTrack): String = error("scheduler is gone")
            override fun introPlaybackMsFor(track: AudioTrack): Long = error("scheduler is gone")
            override fun isResumingTrack(track: AudioTrack): Boolean = error("scheduler is gone")
        }

        buildThroughLavaplayer(IntroFilterFactory(exploding) { _, _ -> })
    }

    @Test
    fun `a real guild player's factory builds through lavaplayer without throwing`() {
        // End to end from the wiring GuildMusicManager actually installs,
        // rather than a factory assembled by this test.
        val playerManager = mockk<com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager>(relaxed = true)
        val captured = slot<PcmFilterFactory>()
        val player = mockk<com.sedmelluq.discord.lavaplayer.player.AudioPlayer>(relaxed = true)
        every { playerManager.createPlayer() } returns player
        every { player.setFilterFactory(capture(captured)) } answers { }
        every { player.playingTrack } returns null

        GuildMusicManager(playerManager, 1L)

        assertFalse(captured.isNull, "GuildMusicManager should have installed a filter factory")
        buildThroughLavaplayer(captured.captured)
    }
}
