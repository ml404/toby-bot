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

class NowDigOnThisCommandTest implements MusicCommandTest {

    NowDigOnThisCommand nowDigOnThisCommand;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        nowDigOnThisCommand = new NowDigOnThisCommand();
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void test_nowDigOnThisCommand_withValidArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        OptionMapping linkOptionalMapping = mock(OptionMapping.class);
        OptionMapping volumeOptionalMapping = mock(OptionMapping.class);
        when(event.getOption("link")).thenReturn(linkOptionalMapping);
        when(event.getOption("volume")).thenReturn(volumeOptionalMapping);
        when(linkOptionalMapping.getAsString()).thenReturn("www.testlink.com");
        when(volumeOptionalMapping.getAsInt()).thenReturn(20);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(2);
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"));
        when(track2.getDuration()).thenReturn(1000L);
        when(playerManager.getMusicManager(guild)).thenReturn(musicManager);
        queue.add(track);
        queue.add(track2);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(track.getUserData()).thenReturn(1);

        //Act
        nowDigOnThisCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(playerManager, times(1)).loadAndPlay(eq(event), eq("www.testlink.com"), eq(false), eq(0), eq(0L), eq(20));
    }

    @Test
    void test_nowDigOnThisCommand_withInvalidPermissionsArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        OptionMapping linkOptionalMapping = mock(OptionMapping.class);
        OptionMapping volumeOptionalMapping = mock(OptionMapping.class);
        when(event.getOption("link")).thenReturn(linkOptionalMapping);
        when(event.getOption("volume")).thenReturn(volumeOptionalMapping);
        when(linkOptionalMapping.getAsString()).thenReturn("www.testlink.com");
        when(volumeOptionalMapping.getAsInt()).thenReturn(20);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(2);
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"));
        when(track2.getDuration()).thenReturn(1000L);
        when(playerManager.getMusicManager(guild)).thenReturn(musicManager);
        queue.add(track);
        queue.add(track2);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(track.getUserData()).thenReturn(1);
        when(requestingUserDto.hasDigPermission()).thenReturn(false);

        //Act
        nowDigOnThisCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(interactionHook, times(1)).sendMessage("I'm gonna put some dirt in your eye Effective Name");
        verify(playerManager, times(0)).loadAndPlay(eq(event), eq("www.testlink.com"), eq(false), eq(0), eq(0L), eq(20));
    }
}