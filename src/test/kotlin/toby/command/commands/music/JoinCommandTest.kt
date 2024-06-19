package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.track
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
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
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMusicMocks()
        clearMocks(configService)
    }

    @Test
    fun test_joinCommand() {
        setUpAudioChannelsWithBotNotInChannel()
        val commandContext = CommandContext(CommandTest.event)

        every { audioPlayer.isPaused } returns false
        every { audioPlayer.playingTrack } returns track
        every { playerManager.isCurrentlyStoppable } returns false
        every { MusicCommandTest.memberVoiceState.channel } returns MusicCommandTest.audioChannelUnion
        every { MusicCommandTest.audioChannelUnion.name } returns "Channel Name"
        val queue: ArrayBlockingQueue<AudioTrack?> = mockk()
        every { trackScheduler.queue } returns queue
        every { CommandTest.member.voiceState } returns MusicCommandTest.memberVoiceState
        every { MusicCommandTest.memberVoiceState.inAudioChannel() } returns true
        every { MusicCommandTest.botMember.hasPermission(Permission.VOICE_CONNECT) } returns true

        // Act
        command.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { MusicCommandTest.audioManager.openAudioConnection(MusicCommandTest.audioChannelUnion) }
        verify(exactly = 1) { playerManager.getMusicManager(CommandTest.guild) }
        verify(exactly = 1) { MusicCommandTest.musicManager.audioPlayer.volume = 100 }
        verify(exactly = 1) { CommandTest.event.hook.sendMessageFormat(
            eq("Connecting to `\uD83D\uDD0A %s` with volume '%s'"),
            eq("Channel Name"),
            eq(100)
        ) }
    }
}
