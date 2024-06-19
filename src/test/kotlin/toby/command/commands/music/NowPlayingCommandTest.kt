package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer

internal class NowPlayingCommandTest : MusicCommandTest {
    lateinit var nowPlayingCommand: NowPlayingCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        nowPlayingCommand = NowPlayingCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearMocks(CommandTest.interactionHook, audioPlayer, MusicCommandTest.track, CommandTest.requestingUserDto)
    }

    @Test
    fun testNowPlaying_withNoCurrentTrack_throwsError() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { audioPlayer.playingTrack } returns null

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("There is no track playing currently") }
    }

    @Test
    fun testNowPlaying_withoutCorrectPermission_throwsError() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { CommandTest.requestingUserDto.musicPermission } returns false

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageFormat(
            "You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name"
        ) }
    }

    @Test
    fun testNowPlaying_withCurrentTrackStream_printsTrack() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.track.userData } returns 1

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage(
            "Now playing `Title` by `Author` (Link: <uri>) with volume '0'"
        ) }
    }

    @Test
    fun testNowPlaying_withCurrentTrackNotStream_printsTrackWithTimestamps() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val audioTrackInfo = AudioTrackInfo("Title", "Author", 1000L, "Identifier", false, "uri")
        every { audioPlayer.playingTrack } returns MusicCommandTest.track
        every { MusicCommandTest.track.info } returns audioTrackInfo
        every { MusicCommandTest.track.userData } returns 1
        every { MusicCommandTest.track.position } returns 1000L
        every { MusicCommandTest.track.duration } returns 3000L

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage(
            "Now playing `Title` by `Author` `[00:00:01/00:00:03]` (Link: <uri>) with volume '0'"
        ) }
    }
}
