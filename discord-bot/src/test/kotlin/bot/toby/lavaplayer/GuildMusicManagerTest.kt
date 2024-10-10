package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class GuildMusicManagerTest {

    @Test
    fun `test GuildMusicManager initialisation`() {
        // Mock AudioPlayerManager
        val mockAudioPlayerManager = mockk<AudioPlayerManager>()

        // Mock AudioPlayer
        val mockAudioPlayer = mockk<AudioPlayer>()

        // Stub AudioPlayerManager's createPlayer() to return mock AudioPlayer
        every { mockAudioPlayerManager.createPlayer() } returns mockAudioPlayer
        every { mockAudioPlayer.addListener(any()) } just Runs


        // Create GuildMusicManager instance using mocked AudioPlayerManager
        val guildMusicManager = GuildMusicManager(mockAudioPlayerManager)

        // Assert that audioPlayer and scheduler are initialized
        assertNotNull(guildMusicManager.audioPlayer)
        assertNotNull(guildMusicManager.scheduler)
    }
}