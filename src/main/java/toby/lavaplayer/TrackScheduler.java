package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.helpers.MusicPlayerHelper;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;
import static toby.helpers.MusicPlayerHelper.nowPlaying;

public class TrackScheduler extends AudioEventAdapter {
    private final AudioPlayer player;
    private BlockingQueue<AudioTrack> queue;
    private boolean isLooping;
    private SlashCommandInteractionEvent event;
    private Integer deleteDelay;
    private Integer previousVolume;


    public boolean isLooping() {
        return isLooping;
    }

    public void setLooping(boolean looping) {
        isLooping = looping;
    }

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
    }

    public void queue(AudioTrack track, long startPosition, int volume) {
        event.getHook().sendMessage("Adding to queue: `")
                .addContent(track.getInfo().title)
                .addContent("` by `")
                .addContent(track.getInfo().author)
                .addContent("`")
                .addContent(String.format(" starting at '%s ms' with volume '%d'", track.getPosition(), volume))
                .queue(invokeDeleteOnMessageResponse(deleteDelay));

        track.setPosition(startPosition);
        track.setUserData(volume);
        synchronized (queue) {
            if (!this.player.startTrack(track, true)) {
                this.queue.offer(track);
            }
        }
    }

    public void queueTrackList(AudioPlaylist playList, int volume) {
        List<AudioTrack> tracks = playList.getTracks();
        event.getHook().sendMessage("Adding to queue: `")
                .addContent(String.valueOf(tracks.size()))
                .addContent("` tracks from playlist `")
                .addContent(playList.getName())
                .addContent("`")
                .queue(invokeDeleteOnMessageResponse(deleteDelay));

        tracks.forEach(track -> {
            track.setUserData(volume);
            boolean hasNotStarted = !this.player.startTrack(track, true);
            if (hasNotStarted) {
                this.queue.offer(track);
            }
        });

    }

    public void nextTrack() {
        synchronized (queue) {
            AudioTrack track = this.queue.poll();
            assert track != null;
            int volume = (int) track.getUserData();
            this.player.setVolume(volume);
            this.player.startTrack(track, false);
        }
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason.mayStartNext) {
            if (isLooping) {
                this.player.startTrack(track.makeClone(), false);
                return;
            }
            PlayerManager instance = PlayerManager.getInstance();
            instance.setCurrentlyStoppable(true);
            if (previousVolume!= null && player.getVolume() != previousVolume) {
                player.setVolume(previousVolume);
                event.getChannel().sendMessageFormat("Setting volume back to '%d' \uD83D\uDD0A", previousVolume).queue(invokeDeleteOnMessageResponse(deleteDelay));
            }
            MusicPlayerHelper.resetMessages(event.getGuild().getIdLong());
            AudioTrack audioTrack = this.queue.poll();
            if (audioTrack != null) {
                nextTrack();
                nowPlaying(event, instance, deleteDelay);
            }
        }
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        if (track.getPosition() == 0L) {
            event.getChannel().sendMessage(String.format("Track %s got stuck, skipping.", track.getInfo().title)).queue(invokeDeleteOnMessageResponse(deleteDelay));
            nextTrack();
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        super.onTrackStart(player, track);
        player.setVolume((Integer) track.getUserData());
        MusicPlayerHelper.nowPlaying(event, PlayerManager.getInstance(), MusicPlayerHelper.deriveDeleteDelayFromTrack(track));
    }

    public boolean stopTrack(boolean isStoppable) {
        if (isStoppable) {
            player.stopTrack();
            return true;
        } else {
            return false;
        }
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public BlockingQueue<AudioTrack> getQueue() {
        return queue;
    }

    public void setQueue(BlockingQueue<AudioTrack> queue) {
        this.queue = queue;
    }

    public SlashCommandInteractionEvent getEvent() {
        return event;
    }

    public void setEvent(SlashCommandInteractionEvent currentTextChannel) {
        this.event = currentTextChannel;
    }

    public void setDeleteDelay(Integer deleteDelay) {
        this.deleteDelay = deleteDelay;
    }

    public Integer getDeleteDelay() {
        return deleteDelay;
    }


    public void setPreviousVolume(Integer previousVolume) {
        this.previousVolume = previousVolume;
    }
}
