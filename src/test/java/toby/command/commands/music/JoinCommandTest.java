package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.jpa.service.IConfigService;

import java.util.concurrent.ArrayBlockingQueue;

import static org.mockito.Mockito.*;

class JoinCommandTest implements MusicCommandTest {

    JoinCommand command;

    @Mock
    IConfigService configService;

    @BeforeEach
    public void setup(){
        setupCommonMusicMocks();
        configService = mock(IConfigService.class);
        command = new JoinCommand(configService);
    }

    @AfterEach
    public void teardown(){
        tearDownCommonMusicMocks();
    }
    @Test
    void test_joinCommand() {
        setUpAudioChannelsWithBotNotInChannel();
        CommandContext commandContext = new CommandContext(event);
        when(audioPlayer.isPaused()).thenReturn(false);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        when(memberVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(audioChannelUnion.getName()).thenReturn("Channel Name");
        ArrayBlockingQueue<AudioTrack> queue = mock(ArrayBlockingQueue.class);
        when(trackScheduler.getQueue()).thenReturn(queue);
        when(member.getVoiceState()).thenReturn(memberVoiceState);
        when(memberVoiceState.inAudioChannel()).thenReturn(true);
        when(botMember.hasPermission(Permission.VOICE_CONNECT)).thenReturn(true);

        //Act
        command.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(audioManager, times(1)).openAudioConnection(audioChannelUnion);
        verify(playerManager, times(1)).getMusicManager(guild);
        verify(musicManager.getAudioPlayer(), times(1)).setVolume(100);
        verify(interactionHook, times(1)).deleteOriginal();
        verify(event.getHook(), times(1)).sendMessageFormat(eq("Connecting to `\uD83D\uDD0A %s` with volume '%s'"), eq("Channel Name"), eq(100));
    }
}