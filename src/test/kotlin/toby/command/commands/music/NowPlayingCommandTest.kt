package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest

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
    }

    @Test
    fun testNowPlaying_withNoCurrentTrack_throwsError() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.playingTrack).thenReturn(null)


        //Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage("There is no track playing currently")
    }

    @Test
    fun testNowPlaying_withoutCorrectPermission_throwsError() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(CommandTest.requestingUserDto.musicPermission).thenReturn(false)


        //Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name")
    }

    @Test
    fun testNowPlaying_withCurrentTrackStream_printsTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)


        //Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Now playing `Title` by `Author` (Link: <uri>) with volume '0'"))
    }

    @Test
    fun testNowPlaying_withCurrentTrackNotStream_printsTrackWithTimestamps() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val audioTrackInfo = AudioTrackInfo("Title", "Author", 1000L, "Identifier", false, "uri")
        Mockito.`when`(MusicCommandTest.audioPlayer.playingTrack)
            .thenReturn(MusicCommandTest.track)
        Mockito.`when`(MusicCommandTest.track.info).thenReturn(audioTrackInfo)
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)
        Mockito.`when`(MusicCommandTest.track.position).thenReturn(1000L)
        Mockito.`when`(MusicCommandTest.track.duration).thenReturn(3000L)
        //Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Now playing `Title` by `Author` `[00:00:01/00:00:03]` (Link: <uri>) with volume '0'"))
    }
}