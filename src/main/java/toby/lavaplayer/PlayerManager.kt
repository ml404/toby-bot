package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;

public class PlayerManager {
    private static PlayerManager INSTANCE;
    private final Map<Long, GuildMusicManager> musicManagers;
    private final AudioPlayerManager audioPlayerManager;
    private boolean currentlyStoppable = true;
    private Integer previousVolume;


    public PlayerManager() {
        this.musicManagers = new HashMap<>();
        this.audioPlayerManager = new DefaultAudioPlayerManager();
        audioPlayerManager.registerSourceManager(new YoutubeAudioSourceManager());
        audioPlayerManager.registerSourceManager(new TwitchStreamAudioSourceManager());
        audioPlayerManager.registerSourceManager(new HttpAudioSourceManager());
        audioPlayerManager.registerSourceManager(new LocalAudioSourceManager());

        AudioSourceManagers.registerRemoteSources(this.audioPlayerManager);
        AudioSourceManagers.registerLocalSource(this.audioPlayerManager);
    }

    public GuildMusicManager getMusicManager(Guild guild) {
        return this.musicManagers.computeIfAbsent(guild.getIdLong(), (guildId) -> {
            final GuildMusicManager guildMusicManager = new GuildMusicManager(this.audioPlayerManager);

            guild.getAudioManager().setSendingHandler(guildMusicManager.getSendHandler());

            return guildMusicManager;
        });
    }

    public synchronized void loadAndPlay(Guild guild, SlashCommandInteractionEvent event, String trackUrl, Boolean isSkippable, Integer deleteDelay, Long startPosition, int volume) {
        final GuildMusicManager musicManager = this.getMusicManager(guild);
        this.currentlyStoppable = isSkippable;
        this.audioPlayerManager.loadItemOrdered(musicManager, trackUrl, getResultHandler(event, musicManager, trackUrl, startPosition, volume, deleteDelay));
    }

    @NotNull
    private AudioLoadResultHandler getResultHandler(SlashCommandInteractionEvent event, GuildMusicManager musicManager, String trackUrl, Long startPosition, int volume, Integer deleteDelay) {
        return new AudioLoadResultHandler() {

            private final TrackScheduler scheduler = musicManager.getScheduler();

            @Override
            public void trackLoaded(AudioTrack track) {
                scheduler.setEvent(event);
                scheduler.setDeleteDelay(deleteDelay);
                scheduler.queue(track, startPosition, volume);
                scheduler.setPreviousVolume(previousVolume);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                scheduler.setEvent(event);
                scheduler.setDeleteDelay(deleteDelay);
                scheduler.queueTrackList(playlist, volume);

            }

            @Override
            public void noMatches() {
                if (event != null) event.getHook().sendMessageFormat("Nothing found for the link '%s'", trackUrl).queue(invokeDeleteOnMessageResponse(deleteDelay));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (event != null)  event.getHook().sendMessageFormat("Could not play: %s", exception.getMessage()).queue(invokeDeleteOnMessageResponse(deleteDelay));
            }
        };
    }

    public static PlayerManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PlayerManager();
        }

        return INSTANCE;
    }

    public boolean isCurrentlyStoppable() {
        return currentlyStoppable;
    }

    public void setCurrentlyStoppable(boolean stoppable) {
        this.currentlyStoppable = stoppable;
    }

    public void setPreviousVolume(Integer previousVolume) {
        this.previousVolume = previousVolume;
    }
}
