package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
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
        AudioTrack track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true ,"uri");
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
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOption("skip")).thenReturn(optionMapping);
        when(optionMapping.getAsInt()).thenReturn(2);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(3);
        AudioTrack track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true ,"uri");
        AudioTrack track3 = mockAudioTrack("Another Title 1", "Another Author 1", "identifier", 1000L, true, "uri");
        queue.add(track);
        queue.add(track2);
        queue.add(track3);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(track.getUserData()).thenReturn(1);

        //Act
        skipCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(trackScheduler, times(1)).setLooping(false);
        verify(trackScheduler, times(2)).nextTrack();
        verify(interactionHook, times(1)).sendMessageFormat(eq("Skipped %d track(s)"), eq(2));
        verify(interactionHook, times(1)).sendMessage(eq("Now playing `Title` by `Author` (Link: <uri>) with volume '0'"));
    }

    @NotNull
    private static AudioTrack mockAudioTrack(String title, String author, String identifier, long songLength, boolean isStream, String uri) {
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo(title, author, songLength, identifier, isStream, uri));
        when(track2.getDuration()).thenReturn(songLength);
        return track2;
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
        AudioTrack track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true ,"uri");
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