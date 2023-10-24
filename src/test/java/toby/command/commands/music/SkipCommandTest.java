package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;

import java.util.concurrent.ArrayBlockingQueue;

import static org.mockito.Mockito.*;

class SkipCommandTest implements MusicCommandTest {

    SkipCommand skipCommand;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        skipCommand = new SkipCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    void test_skipCommand_withValidQueueAndSetup() {
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
            when(track.getUserData()).thenReturn(1);

            //Act
            skipCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

            verify(trackScheduler, times(1)).setLooping(false);
            verify(trackScheduler, times(1)).nextTrack();
            verify(interactionHook, times(1)).sendMessageFormat(eq("Skipped %d track(s)"), eq(1));
    }

    @Test
    void test_skipCommandForMultipleTracks_withValidQueueAndSetup() {
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
        skipCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(trackScheduler, times(1)).setLooping(false);
        verify(trackScheduler, times(2)).nextTrack();
        verify(interactionHook, times(1)).sendMessageFormat(eq("Skipped %d track(s)"), eq(2));
    }

    @Test
    void test_skipCommandWithInvalidAmountOfTracksToSkip_withValidQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOption("skip")).thenReturn(optionMapping);
        when(optionMapping.getAsInt()).thenReturn(-1);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(2);
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"));
        when(track2.getDuration()).thenReturn(1000L);
        queue.add(track);
        queue.add(track2);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(track.getUserData()).thenReturn(1);

        //Act
        skipCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(trackScheduler, times(0)).setLooping(false);
        verify(trackScheduler, times(0)).nextTrack();
        verify(interactionHook, times(1)).sendMessage(eq("You're not too bright, but thanks for trying"));
    }

    @Test
    void test_skipCommandWithValidNumberOfTracksToSkip_withNoQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOption("skip")).thenReturn(optionMapping);
        when(optionMapping.getAsInt()).thenReturn(1);
        when(trackScheduler.getQueue()).thenReturn(null);
        when(track.getUserData()).thenReturn(1);
        when(audioPlayer.getPlayingTrack()).thenReturn(null);

        //Act
        skipCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(trackScheduler, times(0)).setLooping(false);
        verify(trackScheduler, times(0)).nextTrack();
        verify(interactionHook, times(1)).sendMessage(eq("There is no track playing currently"));
    }
}