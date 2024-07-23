package toby.button.buttons

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.button.ButtonContext
import toby.button.ButtonTest
import toby.button.ButtonTest.Companion.configService
import toby.button.ButtonTest.Companion.event
import toby.button.ButtonTest.Companion.mockGuild
import toby.button.ButtonTest.Companion.userService
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.*
import toby.lavaplayer.GuildMusicManager
import toby.lavaplayer.PlayerManager
import toby.lavaplayer.TrackScheduler
import java.util.concurrent.LinkedBlockingQueue

class PausePlayButtonTest : ButtonTest {

    @BeforeEach
    override fun setup() {
        super.setup()
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
        unmockkAll()
    }

    @Test
    fun `test handle ButtonInteractionEvent with pause_play`() {
        every { event.componentId } returns "pause/play"

        val mockScheduler = mockk<TrackScheduler> {
            every { stopTrack(any()) } returns true
            every { queue } returns LinkedBlockingQueue()
            every { isLooping = any() } just Runs
        }

        val mockAudioPlayerManager = mockk<AudioPlayerManager>()
        val mockAudioPlayer = mockk<AudioPlayer>()
        every { mockAudioPlayer.addListener(any()) } just Runs

        every { mockAudioPlayerManager.createPlayer() } returns mockAudioPlayer

        val guildMusicManagerSpy = spyk(GuildMusicManager(mockAudioPlayerManager)) {
            every { scheduler } returns mockScheduler
        }

        mockkObject(PlayerManager) {
            every { PlayerManager.instance.getMusicManager(mockGuild) } returns guildMusicManagerSpy
        }

        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")
        every { userService.getUserById(any(), any()) } returns mockk(relaxed = true)

        PausePlayButton().handle(ButtonContext(event), UserDto(), 0)

        verify { MusicPlayerHelper.changePauseStatusOnTrack(any(), any(), any()) }
    }
}