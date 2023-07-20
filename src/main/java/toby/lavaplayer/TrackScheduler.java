package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import toby.command.ICommand;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static toby.helpers.MusicPlayerHelper.nowPlaying;

public class TrackScheduler extends AudioEventAdapter {
    private final AudioPlayer player;
    private BlockingQueue<AudioTrack> queue;
    private boolean isLooping;
    private IReplyCallback event;
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
        if (!this.player.startTrack(track, true)) {
            this.queue.offer(track);
        }
    }

    public void queue(AudioTrack track) {
        if (!this.player.startTrack(track, true)) {
            this.queue.offer(track);
        }
    }

    public void nextTrack() {
        AudioTrack track = this.queue.poll();
        int volume = (int) track.getUserData();
        this.player.setVolume(volume);
        this.player.startTrack(track, false);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        event.deferReply().queue();
        if (endReason.mayStartNext) {
            int volume = (int) track.getUserData();
            if (isLooping) {
                this.player.startTrack(track.makeClone(), false);
                return;
            }
            PlayerManager.getInstance().setCurrentlyStoppable(true);
            if (player.getVolume() != previousVolume) {
                player.setVolume(previousVolume);
                event.getHook().sendMessageFormat("Setting volume back to '%d' \uD83D\uDD0A", previousVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
            nextTrack();
            nowPlaying(event, player.getPlayingTrack(), deleteDelay, volume);
        }
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

    public IReplyCallback getEvent() {
        return event;
    }

    public void setEvent(IReplyCallback event) {
        this.event = event;
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
