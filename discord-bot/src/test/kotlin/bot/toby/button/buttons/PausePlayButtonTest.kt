package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.configService
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.ButtonTest.Companion.userService
import bot.toby.button.DefaultButtonContext
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import bot.toby.lavaplayer.TrackScheduler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import database.dto.ConfigDto
import database.dto.UserDto
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

        val guildMusicManagerSpy = spyk(GuildMusicManager(mockAudioPlayerManager, 0)) {
            every { scheduler } returns mockScheduler
        }

        mockkObject(PlayerManager) {
            every { PlayerManager.instance.getMusicManager(mockGuild) } returns guildMusicManagerSpy
        }

        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")
        every { userService.getUserById(any(), any()) } returns mockk(relaxed = true)

        PausePlayButton().handle(DefaultButtonContext(event), UserDto(6L, 1L), 5)

        verify { MusicPlayerHelper.changePauseStatusOnTrack(any(), any(), any()) }
    }
}