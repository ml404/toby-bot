package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import java.util.concurrent.ArrayBlockingQueue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;

class ShuffleCommandTest implements MusicCommandTest {

    ShuffleCommand shuffleCommand;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        shuffleCommand = new ShuffleCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    void testShuffleCommand_withValidQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOption("skip")).thenReturn(optionMapping);
        when(optionMapping.getAsInt()).thenReturn(2);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(2);
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"));
        when(track2.getDuration()).thenReturn(1000L);
        queue.add(track);
        queue.add(track2);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(track.getUserData()).thenReturn(1);

        //Act
        shuffleCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(interactionHook, times(1)).sendMessage(eq("The queue has been shuffled 🦧"));
    }

    @Test
    void testShuffleCommand_withNoQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOption("skip")).thenReturn(optionMapping);
        when(optionMapping.getAsInt()).thenReturn(2);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(1);
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"));
        when(track2.getDuration()).thenReturn(1000L);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(track.getUserData()).thenReturn(1);

        //Act
        shuffleCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(interactionHook, times(1)).sendMessage(eq("I can't shuffle a queue that doesn't exist"));
    }
}