package toby.button.buttons

import database.dto.ConfigDto
import database.dto.UserDto
import database.service.*
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
import toby.lavaplayer.GuildMusicManager
import toby.lavaplayer.PlayerManager
import toby.lavaplayer.TrackScheduler
import java.util.concurrent.LinkedBlockingQueue

class StopButtonTest : ButtonTest {


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
    fun `test handle ButtonInteractionEvent with stop`() {
        every { event.componentId } returns "stop"

        val mockScheduler = mockk<TrackScheduler> {
            every { stopTrack(any()) } returns true
            every { queue } returns LinkedBlockingQueue()
            every { isLooping = any() } just Runs
        }

        val musicManager = mockk<GuildMusicManager> {
            every { scheduler } returns mockScheduler
        }

        mockkObject(PlayerManager) {
            every { PlayerManager.instance.getMusicManager(mockGuild) } returns musicManager
        }

        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")
        every { userService.getUserById(any(), any()) } returns mockk(relaxed = true)

        StopButton().handle(ButtonContext(event), UserDto(6L, 1L), 0)

        verify { MusicPlayerHelper.stopSong(any(), any(), any(), any()) }
    }
}