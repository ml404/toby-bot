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
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.ICommand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerManager {
    private static PlayerManager INSTANCE;

    private final Map<Long, GuildMusicManager> musicManagers;
    private final AudioPlayerManager audioPlayerManager;
    private boolean currentlyStoppable = true;
    private boolean isPlayingIntro;


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

    public void loadAndPlay(TextChannel channel, String trackUrl, Integer deleteDelay) {
        loadAndPlay(channel, trackUrl, true, false, deleteDelay);
    }

    public void loadAndPlay(TextChannel channel, String trackUrl, Boolean isSkippable, Boolean isIntro, Integer deleteDelay) {
        final GuildMusicManager musicManager = this.getMusicManager(channel.getGuild());
        this.currentlyStoppable = isSkippable;
        this.isPlayingIntro = isIntro;
        this.audioPlayerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {

            private final TrackScheduler scheduler = musicManager.getScheduler();

            @Override
            public void trackLoaded(AudioTrack track) {
                scheduler.setCurrentTextChannel(channel);
                scheduler.setDeleteDelay(deleteDelay);
                scheduler.queue(track);

                channel.sendMessage("Adding to queue: `")
                        .append(track.getInfo().title)
                        .append("` by `")
                        .append(track.getInfo().author)
                        .append('`')
                        .queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                final List<AudioTrack> tracks = playlist.getTracks();
                scheduler.setCurrentTextChannel(channel);
                scheduler.setDeleteDelay(deleteDelay);

                channel.sendMessage("Adding to queue: `")
                        .append(String.valueOf(tracks.size()))
                        .append("` tracks from playlist `")
                        .append(playlist.getName())
                        .append('`')
                        .queue(message -> ICommand.deleteAfter(message, deleteDelay));

                for (final AudioTrack track : tracks) {
                    scheduler.queue(track);
                }
            }

            @Override
            public void noMatches() {
                channel.sendMessageFormat("Nothing found for the link '%s'", trackUrl).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                channel.sendMessageFormat("Could not play: %s", exception.getMessage()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
        });
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

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }

    public boolean isPlayingIntro() {
        return isPlayingIntro;
    }

    public void setPlayingIntro(boolean isPlayingIntro) {
        this.isPlayingIntro = isPlayingIntro;
    }
}
