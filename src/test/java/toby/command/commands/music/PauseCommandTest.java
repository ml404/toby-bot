package toby.command.commands.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import java.util.concurrent.ArrayBlockingQueue;

import static org.mockito.Mockito.*;

class PauseCommandTest implements MusicCommandTest {

    private PauseCommand pauseCommand;

    @BeforeEach
    void setup() {
        setupCommonMusicMocks();
        pauseCommand = new PauseCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    public void test_pauseMethod_withCorrectChannels_andPausableTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);

        //Act
        pauseCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(event.getChannel(), times(1)).sendMessage("Pausing: `");
        verify(messageCreateAction, times(1)).addContent("Title");
        verify(messageCreateAction, times(1)).addContent("` by `");
        verify(messageCreateAction, times(1)).addContent("Author");
        verify(messageCreateAction, times(1)).addContent("`");
    }

    @Test
    public void test_pauseMethod_withCorrectChannels_andNonPausableTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);

        //Act
        pauseCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(interactionHook, times(1)).sendMessageFormat(eq("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!"), eq("00:00:01"), eq("00:00:01"));
    }

    @Test
    public void test_pauseMethod_withCorrectChannels_andNonPausableTrack_AndAQueue() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(1);
        queue.add(track);
        when(trackScheduler.getQueue()).thenReturn(queue);

        //Act
        pauseCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(interactionHook, times(1)).sendMessage(eq("Our daddy taught us not to be ashamed of our playlists"));
    }
}