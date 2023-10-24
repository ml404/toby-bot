package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.mockito.Mock;
import toby.command.commands.CommandTest;
import toby.lavaplayer.AudioPlayerSendHandler;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

import java.util.concurrent.ArrayBlockingQueue;

import static org.mockito.Mockito.*;

public interface MusicCommandTest extends CommandTest {

    @Mock
    GuildVoiceState memberVoiceState = mock(GuildVoiceState.class);
    @Mock
    GuildVoiceState botVoiceState = mock(GuildVoiceState.class);
    @Mock
    Member botMember = mock(Member.class);
    @Mock
    PlayerManager playerManager = mock(PlayerManager.class);
    @Mock
    GuildMusicManager musicManager = mock(GuildMusicManager.class);
    @Mock
    AudioPlayer audioPlayer = mock(AudioPlayer.class);
    @Mock
    AudioManager audioManager = mock(AudioManager.class);
    @Mock
    AudioPlayerSendHandler audioPlayerSendHandler = mock(AudioPlayerSendHandler.class);
    @Mock
    AudioTrack track = mock(AudioTrack.class);
    @Mock
    TrackScheduler trackScheduler = mock(TrackScheduler.class);
    @Mock
    AudioChannelUnion audioChannelUnion = mock(AudioChannelUnion.class);

    default void setupCommonMusicMocks() {
        setUpCommonMocks();
        when(playerManager.getMusicManager(guild)).thenReturn(musicManager);
        when(musicManager.getAudioPlayer()).thenReturn(audioPlayer);
        when(musicManager.getSendHandler()).thenReturn(audioPlayerSendHandler);
        when(guild.getAudioManager()).thenReturn(audioManager);
        when(audioPlayer.getPlayingTrack()).thenReturn(track);
        when(track.getInfo()).thenReturn(new AudioTrackInfo("Title", "Author", 20L, "Identifier", true, "uri"));
        when(musicManager.getScheduler()).thenReturn(trackScheduler);
        when(trackScheduler.getQueue()).thenReturn(new ArrayBlockingQueue<>(1));
        when(track.getDuration()).thenReturn(1000L);
    }

    default void tearDownCommonMusicMocks() {
        tearDownCommonMocks();
        reset(playerManager);
        reset(musicManager);
        reset(guild);
        reset(trackScheduler);
        reset(track);
        reset(audioPlayer);
        reset(audioChannelUnion);
    }

    default void setUpAudioChannelsWithBotAndMemberInSameChannel() {
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        when(member.getVoiceState()).thenReturn(guildVoiceState);
        when(guildVoiceState.inAudioChannel()).thenReturn(true);
        when(guildVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(guild.getAudioManager()).thenReturn(audioManager);
        when(memberVoiceState.inAudioChannel()).thenReturn(true);
        when(guildVoiceState.inAudioChannel()).thenReturn(true);
    }

    default void setUpAudioChannelsWithBotNotInChannel() {
        when(guild.getSelfMember()).thenReturn(botMember);
        when(member.getVoiceState()).thenReturn(memberVoiceState);
        when(botMember.getVoiceState()).thenReturn(botVoiceState);
        when(memberVoiceState.inAudioChannel()).thenReturn(true);
        when(memberVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(botVoiceState.inAudioChannel()).thenReturn(false);
        when(botVoiceState.getChannel()).thenReturn(null);
        when(guild.getAudioManager()).thenReturn(audioManager);
    }

    default void setUpAudioChannelsWithUserNotInChannel() {
        AudioManager audioManager = mock(AudioManager.class);
        when(guild.getSelfMember()).thenReturn(botMember);
        when(member.getVoiceState()).thenReturn(memberVoiceState);
        when(botMember.getVoiceState()).thenReturn(botVoiceState);
        when(memberVoiceState.inAudioChannel()).thenReturn(false);
        when(memberVoiceState.getChannel()).thenReturn(null);
        when(botVoiceState.inAudioChannel()).thenReturn(true);
        when(botVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(guild.getAudioManager()).thenReturn(audioManager);
    }

    default void setUpAudioChannelsWithUserAndBotInDifferentChannels() {
        when(guild.getSelfMember()).thenReturn(botMember);
        when(member.getVoiceState()).thenReturn(memberVoiceState);
        when(botMember.getVoiceState()).thenReturn(botVoiceState);
        when(memberVoiceState.inAudioChannel()).thenReturn(true);
        when(memberVoiceState.getChannel()).thenReturn(mock(AudioChannelUnion.class));
        when(botVoiceState.inAudioChannel()).thenReturn(true);
        when(botVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(guild.getAudioManager()).thenReturn(audioManager);
    }

}
