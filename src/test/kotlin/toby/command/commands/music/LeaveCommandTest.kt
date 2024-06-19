package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import io.mockk.*
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.commands.music.MusicCommandTest.Companion.musicManager
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import toby.jpa.service.IConfigService
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
        clearMocks(configService)
    }

    @Test
    fun test_leaveCommand() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)

        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        every { MusicCommandTest.memberVoiceState.channel } returns MusicCommandTest.audioChannelUnion
        every { MusicCommandTest.audioChannelUnion.name } returns "Channel Name"
        val queue: LinkedBlockingQueue<AudioTrack?> = mockk()
        every { trackScheduler.queue } returns queue

        // Act
        command.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.event.hook.sendMessageFormat(
            eq("Disconnecting from `\uD83D\uDD0A %s`"),
            eq("Channel Name")
        ) }
        verify(exactly = 1) { musicManager.scheduler.isLooping = false }
        verify(exactly = 1) { musicManager.scheduler.queue.clear() }
        verify(exactly = 1) { musicManager.audioPlayer.stopTrack() }
        verify(exactly = 1) { musicManager.audioPlayer.volume = 100 }
        verify(exactly = 1) { MusicCommandTest.audioManager.closeAudioConnection() }
    }
}
