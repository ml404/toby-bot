package toby.command.commands.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import static org.mockito.Mockito.*;

class LoopCommandTest implements MusicCommandTest {

    LoopCommand loopCommand;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        loopCommand = new LoopCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    void test_looping_whenNotCurrentlyLooping() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        when(trackScheduler.isLooping()).thenReturn(false);

        //Act
        loopCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("The Player has been set to **%s**"), eq("looping"));
        verify(trackScheduler, times(1)).setLooping(true);
    }

    @Test
    void test_looping_whenCurrentlyLooping() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        when(trackScheduler.isLooping()).thenReturn(true);

        //Act
        loopCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("The Player has been set to **%s**"), eq("not looping"));
        verify(trackScheduler, times(1)).setLooping(false);
    }
}