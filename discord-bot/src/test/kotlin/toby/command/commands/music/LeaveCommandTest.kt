package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import database.service.IConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.commands.music.MusicCommandTest.Companion.audioChannelUnion
import toby.command.commands.music.MusicCommandTest.Companion.audioManager
import toby.command.commands.music.MusicCommandTest.Companion.memberVoiceState
import toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.musicManager
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import toby.command.commands.music.channel.LeaveCommand
import java.util.concurrent.LinkedBlockingQueue

internal class LeaveCommandTest : MusicCommandTest {
    lateinit var command: LeaveCommand
    lateinit var configService: IConfigService

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        configService = mockk()
        command = LeaveCommand(configService)
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMusicMocks()
        unmockkAll()
    }

    @Test
    fun test_leaveCommand() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)

        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false
        every { memberVoiceState.channel } returns audioChannelUnion
        every { audioChannelUnion.name } returns "Channel Name"
        val queue: LinkedBlockingQueue<AudioTrack?> = mockk(relaxed = true)
        every { trackScheduler.queue } returns queue
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns mockk(relaxed = true) {
            every { value } returns "20"
        }

        // Act
        command.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        val scheduler = musicManager.scheduler
        // Assert
        verify(exactly = 1) { event.hook.sendMessage("Disconnecting from `\uD83D\uDD0A Channel Name`") }
        verify(exactly = 1) { scheduler.isLooping = false }
        verify(exactly = 1) { queue.clear() }
        verify(exactly = 1) { mockAudioPlayer.stopTrack() }
        verify(exactly = 1) { mockAudioPlayer.volume = 20 }
        verify(exactly = 1) { audioManager.closeAudioConnection() }
    }
}
