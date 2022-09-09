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
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.ICommand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public void loadAndPlayChannel(TextChannel channel,String trackUrl, Boolean isSkippable, Integer deleteDelay, Long startPosition) {
        final GuildMusicManager musicManager = this.getMusicManager(channel.getGuild());
        this.currentlyStoppable = isSkippable;
        this.audioPlayerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {

            private final TrackScheduler scheduler = musicManager.getScheduler();

            @Override
            public void trackLoaded(AudioTrack track) {
                scheduler.setDeleteDelay(deleteDelay);
                scheduler.queue(track, startPosition);
                scheduler.setPreviousVolume(previousVolume);

                channel.sendMessage("Adding to queue: `")
                        .addContent(track.getInfo().title)
                        .addContent("` by `")
                        .addContent(track.getInfo().author)
                        .addContent("`")
                        .addContent(String.format(" starting at '%s ms'", startPosition))
                        .queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                final List<AudioTrack> tracks = playlist.getTracks();
                scheduler.setDeleteDelay(deleteDelay);

                channel.sendMessage("Adding to queue: `")
                        .addContent(String.valueOf(tracks.size()))
                        .addContent("` tracks from playlist `")
                        .addContent(playlist.getName())
                        .addContent("`")
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

    public void loadAndPlay(SlashCommandInteractionEvent event, String trackUrl, Boolean isSkippable, Integer deleteDelay, Long startPosition) {
        final GuildMusicManager musicManager = this.getMusicManager(event.getGuild());
        this.currentlyStoppable = isSkippable;
        this.audioPlayerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {

            private final TrackScheduler scheduler = musicManager.getScheduler();

            @Override
            public void trackLoaded(AudioTrack track) {
                scheduler.setEvent(event);
                scheduler.setDeleteDelay(deleteDelay);
                scheduler.queue(track, startPosition);
                scheduler.setPreviousVolume(previousVolume);

                event.deferReply()
                        .addContent("Adding to queue: `")
                        .addContent(track.getInfo().title)
                        .addContent("` by `")
                        .addContent(track.getInfo().author)
                        .addContent("`")
                        .addContent(String.format(" starting at '%s ms'", startPosition))
                        .queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                final List<AudioTrack> tracks = playlist.getTracks();
                scheduler.setEvent(event);
                scheduler.setDeleteDelay(deleteDelay);

                event.getHook().sendMessage("Adding to queue: `")
                        .addContent(String.valueOf(tracks.size()))
                        .addContent("` tracks from playlist `")
                        .addContent(playlist.getName())
                        .addContent("`")
                        .queue(message -> ICommand.deleteAfter(message, deleteDelay));

                for (final AudioTrack track : tracks) {
                    scheduler.queue(track);
                }
            }

            @Override
            public void noMatches() {
                event.getHook().sendMessageFormat("Nothing found for the link '%s'", trackUrl).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                event.getHook().sendMessageFormat("Could not play: %s", exception.getMessage()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
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

    public void setPreviousVolume(Integer previousVolume) {
         this.previousVolume = previousVolume;
    }
}
