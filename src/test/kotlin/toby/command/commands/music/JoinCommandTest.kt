package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.guild
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.requestingUserDto
import toby.command.commands.music.MusicCommandTest.Companion.audioChannelUnion
import toby.command.commands.music.MusicCommandTest.Companion.audioManager
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.botMember
import toby.command.commands.music.MusicCommandTest.Companion.memberVoiceState
import toby.command.commands.music.MusicCommandTest.Companion.musicManager
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.track
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import java.util.concurrent.ArrayBlockingQueue

internal class JoinCommandTest : MusicCommandTest {
    lateinit var command: JoinCommand
    lateinit var configService: IConfigService

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        configService = mockk<IConfigService>()
        command = JoinCommand(configService)
        every { configService.getConfigByName(any(), any()) } returns ConfigDto("", "100", "1")
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMusicMocks()
        clearMocks(configService)
    }

    @Test
    fun test_joinCommand() {
        setUpAudioChannelsWithBotNotInChannel()
        val commandContext = CommandContext(event)

        every { audioPlayer.isPaused } returns false
        every { audioPlayer.playingTrack } returns track
        every { playerManager.isCurrentlyStoppable } returns false
        every { memberVoiceState.channel } returns audioChannelUnion
        every { audioChannelUnion.name } returns "Channel Name"
        val queue: ArrayBlockingQueue<AudioTrack?> = mockk()
        every { trackScheduler.queue } returns queue
        every { member.voiceState } returns memberVoiceState
        every { memberVoiceState.inAudioChannel() } returns true
        every { botMember.hasPermission(Permission.VOICE_CONNECT) } returns true

        // Act
        command.handleMusicCommand(
            commandContext,
            playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { audioManager.openAudioConnection(audioChannelUnion) }
        verify(exactly = 1) { playerManager.getMusicManager(guild) }
        verify(exactly = 1) { audioPlayer.volume = 100 }
        verify(exactly = 1) { event.hook.sendMessage(eq("Connecting to `\uD83D\uDD0A Channel Name` with volume '100'")) }
    }
}
