package toby.command.commands.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import static org.mockito.Mockito.*;

class ResumeCommandTest implements MusicCommandTest {

    private ResumeCommand resumeCommand;

    @BeforeEach
    void setup() {
        setupCommonMusicMocks();
        resumeCommand = new ResumeCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    public void test_resumeMethod_withCorrectChannels_andPausableTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(interactionHook.sendMessage("Resuming: ")).thenReturn(webhookMessageCreateAction);
        when(audioPlayer.isPaused()).thenReturn(true);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);

        //Act
        resumeCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(event.getHook(), times(1)).sendMessage("Resuming: `");
        verify(webhookMessageCreateAction, times(1)).addContent("Title");
        verify(webhookMessageCreateAction, times(1)).addContent("` by `");
        verify(webhookMessageCreateAction, times(1)).addContent("Author");
        verify(webhookMessageCreateAction, times(1)).addContent("`");
        verify(audioPlayer, times(1)).isPaused();
        verify(audioPlayer, times(1)).setPaused(false);
    }
}