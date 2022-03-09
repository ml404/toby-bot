package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.ICommand;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static toby.helpers.MusicPlayerHelper.nowPlaying;

public class TrackScheduler extends AudioEventAdapter {
    private final AudioPlayer player;
    private BlockingQueue<AudioTrack> queue;
    private boolean isLooping;
    private TextChannel currentTextChannel;
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

    public void queue(AudioTrack track) {
        if (!this.player.startTrack(track, true)) {
            this.queue.offer(track);
        }
    }

    public void nextTrack() {
        AudioTrack track = this.queue.poll();
        this.player.startTrack(track, false);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason.mayStartNext) {
            if (isLooping) {
                this.player.startTrack(track.makeClone(), false);
                return;
            }
            PlayerManager.getInstance().setCurrentlyStoppable(true);
            if (player.getVolume() != previousVolume) {
                player.setVolume(previousVolume);
                currentTextChannel.sendMessage(String.format("Setting volume back to '%d' \uD83D\uDD0A", previousVolume)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
            nextTrack();
            nowPlaying(currentTextChannel, player.getPlayingTrack(), deleteDelay);
        }
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        getCurrentTextChannel().sendMessage(String.format("Track %s got stuck, skipping.", track.getInfo().title)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        nextTrack();
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

    public TextChannel getCurrentTextChannel() {
        return currentTextChannel;
    }

    public void setCurrentTextChannel(TextChannel currentTextChannel) {
        this.currentTextChannel = currentTextChannel;
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
