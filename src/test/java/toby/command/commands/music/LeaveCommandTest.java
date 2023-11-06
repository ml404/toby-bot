package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.jpa.service.IConfigService;

import java.util.concurrent.ArrayBlockingQueue;

import static org.mockito.Mockito.*;

class LeaveCommandTest implements MusicCommandTest {

    LeaveCommand command;

    @Mock
    IConfigService configService;

    @BeforeEach
    public void setup(){
        setupCommonMusicMocks();
        configService = mock(IConfigService.class);
        command = new LeaveCommand(configService);
    }

    @AfterEach
    public void teardown(){
        tearDownCommonMusicMocks();
    }
    @Test
    void test_leaveCommand() {
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        when(memberVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(audioChannelUnion.getName()).thenReturn("Channel Name");
        ArrayBlockingQueue<AudioTrack> queue = mock(ArrayBlockingQueue.class);
        when(trackScheduler.getQueue()).thenReturn(queue);

        //Act
        command.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(event.getHook(), times(1)).sendMessageFormat(eq("Disconnecting from `\uD83D\uDD0A %s`"), eq("Channel Name"));
        verify(musicManager.getScheduler(), times(1)).setLooping(false);
        verify(musicManager.getScheduler().getQueue(), times(1)).clear();
        verify(musicManager.getAudioPlayer(), times(1)).stopTrack();
        verify(musicManager.getAudioPlayer(), times(1)).setVolume(100);
        verify(audioManager, times(1)).closeAudioConnection();
    }
}