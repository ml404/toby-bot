package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.helpers.MusicPlayerHelper;

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
        track.setPosition(startPosition);
        track.setUserData(volume);
        synchronized (queue) {
            if (!this.player.startTrack(track, true)) {
                this.queue.offer(track);
            }
        }
    }

    public void queue(AudioTrack track) {
        if (!this.player.startTrack(track, true)) {
            this.queue.offer(track);
        }
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
            int volume = (int) track.getUserData();
            if (isLooping) {
                this.player.startTrack(track.makeClone(), false);
                return;
            }
            PlayerManager.getInstance().setCurrentlyStoppable(true);
            if (player.getVolume() != previousVolume) {
                player.setVolume(previousVolume);
                event.getChannel().sendMessageFormat("Setting volume back to '%d' \uD83D\uDD0A", previousVolume).queue(invokeDeleteOnMessageResponse(deleteDelay));
            }
            AudioTrack audioTrack = this.queue.poll();
            if (audioTrack != null) {
                nextTrack();
                nowPlaying(event, PlayerManager.getInstance(), volume, deleteDelay);
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
    public void onTrackStart(AudioPlayer player, AudioTrack track){
        MusicPlayerHelper.nowPlaying(event, PlayerManager.getInstance(), (Integer) track.getUserData(), MusicPlayerHelper.deriveDeleteDelayFromTrack(track));
        super.onTrackStart(player, track);
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
