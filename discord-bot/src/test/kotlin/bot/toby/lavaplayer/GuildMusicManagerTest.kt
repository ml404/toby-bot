package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuildMusicManagerTest {

    private val format = Pcm16AudioDataFormat(2, 48_000, 960, true)
    private val output: UniversalPcmAudioFilter = mockk(relaxed = true)

    private fun manager(factory: CapturingSlot<PcmFilterFactory>? = null): GuildMusicManager {
        val playerManager = mockk<AudioPlayerManager>()
        val player = mockk<AudioPlayer>(relaxed = true)
        every { playerManager.createPlayer() } returns player
        every { player.addListener(any()) } just Runs
        // Nothing playing, so an intro takes the plain start path rather than
        // preempting — this test is about the wiring, not the preemption.
        every { player.playingTrack } returns null
        if (factory != null) {
            every { player.setFilterFactory(capture(factory)) } just Runs
        } else {
            every { player.setFilterFactory(any()) } just Runs
        }
        return GuildMusicManager(playerManager, 0)
    }

    @Test
    fun `test GuildMusicManager initialisation`() {
        val guildMusicManager = manager()

        assertNotNull(guildMusicManager.audioPlayer)
        assertNotNull(guildMusicManager.scheduler)
    }

    @Test
    fun `the guild player is given the intro filter factory`() {
        val factory = slot<PcmFilterFactory>()

        manager(factory)

        assertInstanceOf(IntroFilterFactory::class.java, factory.captured)
    }

    @Test
    fun `the factory reads this guild's own scheduler`() {
        // The wiring is what makes any of the fade real at runtime, and it was
        // the one part nothing checked. Every filter test in this package runs
        // against a hand-built context, so a factory pointed at the wrong
        // scheduler — or at none — would leave all of them green while nothing
        // faded and nothing was ever measured.
        val factory = slot<PcmFilterFactory>()
        val guildMusicManager = manager(factory)

        val intro = mockk<AudioTrack>(relaxed = true)
        // Real info: the queue snapshot published on every change maps each
        // track, and a relaxed mock's identifier is null.
        every { intro.info } returns
            AudioTrackInfo("Intro", "Author", 12_000L, "identifier", false, "http://example.com")
        every { intro.duration } returns 12_000L
        guildMusicManager.scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")
        // The player is on the intro, which is how the factory identifies it —
        // lavaplayer passes null for the track.
        every { guildMusicManager.audioPlayer.playingTrack } returns intro

        assertTrue(
            factory.captured.buildChain(null, format, output).isNotEmpty(),
            "the factory should see this scheduler's intro bookkeeping",
        )

        every { guildMusicManager.audioPlayer.playingTrack } returns mockk<AudioTrack>(relaxed = true)
        assertTrue(
            factory.captured.buildChain(null, format, output).isEmpty(),
            "an unrelated track should still get an empty chain",
        )
    }
}
