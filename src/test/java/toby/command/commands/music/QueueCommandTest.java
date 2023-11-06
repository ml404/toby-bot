package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import java.util.concurrent.ArrayBlockingQueue;

import static org.mockito.Mockito.*;

class QueueCommandTest implements MusicCommandTest {

    QueueCommand queueCommand;

    @BeforeEach
    public void setup(){
        setupCommonMusicMocks();
        queueCommand = new QueueCommand();

    }

    public void tearDown(){
        tearDownCommonMusicMocks();
    }

    @Test
    void testQueue_WithNoTrackInTheQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);

        //Act
        queueCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(event.getHook(), times(1)).sendMessage("The queue is currently empty");
    }

    @Test
    void testQueue_WithOneTrackInTheQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(1);
        queue.add(track);
        when(trackScheduler.getQueue()).thenReturn(queue);

        //Act
        queueCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(event.getHook(), times(1)).sendMessage("**Current Queue:**\n");
        verify(webhookMessageCreateAction, times(1)).addContent("#");
        verify(webhookMessageCreateAction, times(1)).addContent("1");
        verify(webhookMessageCreateAction, times(1)).addContent(" `");
        verify(webhookMessageCreateAction, times(1)).addContent("Title");
        verify(webhookMessageCreateAction, times(1)).addContent(" by ");
        verify(webhookMessageCreateAction, times(1)).addContent("Author");
        verify(webhookMessageCreateAction, times(1)).addContent("` [`");
        verify(webhookMessageCreateAction, times(1)).addContent("00:00:01");
        verify(webhookMessageCreateAction, times(1)).addContent("`]\n");
    }

    @Test
    void testQueue_WithMultipleTracksInTheQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(2);
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"));
        when(track2.getDuration()).thenReturn(1000L);
        queue.add(track);
        queue.add(track2);
        when(trackScheduler.getQueue()).thenReturn(queue);

        //Act
        queueCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(event.getHook(), times(1)).sendMessage("**Current Queue:**\n");
        verify(webhookMessageCreateAction, times(2)).addContent("#");
        verify(webhookMessageCreateAction, times(1)).addContent("1");
        verify(webhookMessageCreateAction, times(2)).addContent(" `");
        verify(webhookMessageCreateAction, times(1)).addContent("Title");
        verify(webhookMessageCreateAction, times(2)).addContent(" by ");
        verify(webhookMessageCreateAction, times(1)).addContent("Author");
        verify(webhookMessageCreateAction, times(2)).addContent("` [`");
        verify(webhookMessageCreateAction, times(2)).addContent("00:00:01");
        verify(webhookMessageCreateAction, times(2)).addContent("`]\n");
        verify(webhookMessageCreateAction, times(1)).addContent("2");
        verify(webhookMessageCreateAction, times(1)).addContent("Another Title");
        verify(webhookMessageCreateAction, times(1)).addContent("Another Author");

    }
}