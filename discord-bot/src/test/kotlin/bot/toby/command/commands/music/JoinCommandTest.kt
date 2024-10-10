package bot.toby.command.commands.music

import bot.toby.command.CommandContext
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.commands.music.MusicCommandTest.Companion.audioManager
import bot.toby.command.commands.music.MusicCommandTest.Companion.botMember
import bot.toby.command.commands.music.MusicCommandTest.Companion.memberVoiceState
import bot.toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import bot.toby.command.commands.music.MusicCommandTest.Companion.playerManager
import bot.toby.command.commands.music.MusicCommandTest.Companion.track
import bot.toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import bot.toby.command.commands.music.channel.JoinCommand
import database.dto.ConfigDto
import database.service.IConfigService
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        clearAllMocks()
    }

    @Test
    fun test_joinCommand() {
        setUpAudioChannelsWithBotNotInChannel()
        // Setup mocks and stubs
        val voiceChannel = mockk<VoiceChannel>() // Mock specific type if it's expected to be a VoiceChannel
        val audioChannelUnion = mockk<AudioChannelUnion>()
        val commandContext = CommandContext(event)

        // Setup audio channel mocks
        every { audioChannelUnion.asVoiceChannel() } returns voiceChannel
        every { voiceChannel.name } returns "Channel Name"

        // Mock behaviors for audio player and other services
        every { mockAudioPlayer.isPaused } returns false
        every { mockAudioPlayer.playingTrack } returns track
        every { playerManager.isCurrentlyStoppable } returns false
        every { memberVoiceState.channel } returns audioChannelUnion
        every { trackScheduler.queue } returns mockk(relaxed = true)
        every { member.voiceState } returns memberVoiceState
        every { memberVoiceState.inAudioChannel() } returns true
        every { botMember.hasPermission(Permission.VOICE_CONNECT) } returns true
        every { audioManager.openAudioConnection(any()) } just Runs
        every { playerManager.getMusicManager(any()) } returns mockk(relaxed = true) {
            every { audioPlayer } returns mockAudioPlayer
        }
        every { mockAudioPlayer.volume = 100 } just Runs
        every { event.hook.sendMessage(any<String>()) } returns mockk(relaxed = true)

        // Act
        command.handleMusicCommand(
            commandContext,
            playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { audioManager.openAudioConnection(voiceChannel) }
        verify(exactly = 1) { playerManager.getMusicManager(guild) }
        verify(exactly = 1) { mockAudioPlayer.volume = 100 }
        verify(exactly = 1) { event.hook.sendMessage(eq("Connecting to `\uD83D\uDD0A Channel Name` with volume '100'")) }
    }

}
