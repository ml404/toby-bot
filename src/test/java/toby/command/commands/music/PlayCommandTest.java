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

class PlayCommandTest implements MusicCommandTest {

    PlayCommand playCommand;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        playCommand = new PlayCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    void test_playcommand_withValidArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        OptionMapping linkOptionalMapping = mock(OptionMapping.class);
        OptionMapping typeOptionalMapping = mock(OptionMapping.class);
        OptionMapping volumeOptionalMapping = mock(OptionMapping.class);
        when(event.getOption("type")).thenReturn(typeOptionalMapping);
        when(event.getOption("link")).thenReturn(linkOptionalMapping);
        when(event.getOption("volume")).thenReturn(volumeOptionalMapping);
        when(typeOptionalMapping.getAsString()).thenReturn("link");
        when(linkOptionalMapping.getAsString()).thenReturn("www.testlink.com");
        when(volumeOptionalMapping.getAsInt()).thenReturn(20);
        ArrayBlockingQueue queue = new ArrayBlockingQueue<>(2);
        AudioTrack track2 = mock(AudioTrack.class);
        when(track2.getInfo()).thenReturn(new AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"));
        when(track2.getDuration()).thenReturn(1000L);
        queue.add(track);
        queue.add(track2);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(track.getUserData()).thenReturn(1);

        //Act
        playCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        verify(playerManager, times(1)).loadAndPlay(eq(guild), eq(event), eq("www.testlink.com"), eq(true), eq(0), eq(0L), eq(20));
    }
}