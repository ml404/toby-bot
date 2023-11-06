package toby.command.commands.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class StopCommandTest implements MusicCommandTest {

    StopCommand stopCommand;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        stopCommand = new StopCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    void test_callStopCommand_withBotAndUserBothInSameChannels() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);

        //Act
        stopCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessage(eq("The player has been stopped and the queue has been cleared"));
    }

    @Test
    void test_callStopCommand_withBotNotInChannelAndUserInChannel() {
        //Arrange
        setUpAudioChannelsWithBotNotInChannel();
        CommandContext commandContext = new CommandContext(event);

        //Act
        stopCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessage(eq("I need to be in a voice channel for this to work"));
    }

    @Test
    void test_callStopCommand_withUserNotInChannelAndBotInChannel() {
        //Arrange
        setUpAudioChannelsWithUserNotInChannel();
        CommandContext commandContext = new CommandContext(event);

        //Act
        stopCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessage(eq("You need to be in a voice channel for this command to work"));
    }

    @Test
    void test_callStopCommand_withUserInChannelAndBotInChannel_ButChannelsAreDifferent() {
        //Arrange
        setUpAudioChannelsWithUserAndBotInDifferentChannels();
        CommandContext commandContext = new CommandContext(event);

        //Act
        stopCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessage(eq("You need to be in the same voice channel as me for this to work"));
    }
}