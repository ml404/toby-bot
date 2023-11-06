package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import static org.mockito.Mockito.*;

class NowPlayingCommandTest implements MusicCommandTest {

    NowPlayingCommand nowPlayingCommand;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        nowPlayingCommand = new NowPlayingCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    void testNowPlaying_withNoCurrentTrack_throwsError() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.getPlayingTrack()).thenReturn(null);


        //Act
        nowPlayingCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessage("There is no track playing currently");

    }

    @Test
    void testNowPlaying_withoutCorrectPermission_throwsError() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(requestingUserDto.hasMusicPermission()).thenReturn(false);


        //Act
        nowPlayingCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name");

    }

    @Test
    void testNowPlaying_withCurrentTrackStream_printsTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);

        //Act
        nowPlayingCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessage(eq("Now playing `Title` by `Author` (Link: <uri>) "));
    }

    @Test
    void testNowPlaying_withCurrentTrackNotStream_printsTrackWithTimestamps() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        AudioTrackInfo audioTrackInfo = new AudioTrackInfo("Title", "Author", 1000L, "Identifier", false, "uri");
        when(audioPlayer.getPlayingTrack()).thenReturn(track);
        when(track.getInfo()).thenReturn(audioTrackInfo);
        when(track.getPosition()).thenReturn(1000L);
        when(track.getDuration()).thenReturn(3000L);
        //Act
        nowPlayingCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessage(eq("Now playing `Title` by `Author` `[00:00:01/00:00:03]` (Link: <uri>) "));
    }
}