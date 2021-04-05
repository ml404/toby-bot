package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.commands.music.QueueCommand;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TrackScheduler extends AudioEventAdapter {
    private final AudioPlayer player;
    private BlockingQueue<AudioTrack> queue;
    private boolean isLooping;
    private TextChannel currentTextChannel;


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

    public void queue(AudioTrack track, TextChannel channel) {
        this.currentTextChannel = channel;
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
            nextTrack();
            nowPlaying(currentTextChannel, player.getPlayingTrack());
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


    private void nowPlaying(TextChannel channel, AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        long duration = track.getDuration();
        String songDuration = QueueCommand.formatTime(duration);
        String nowPlaying = String.format("Now playing `%s` by `%s` `[%s]` (Link: <%s>) ", info.title, info.author, songDuration, info.uri);
        channel.sendMessage(nowPlaying).queue();
    }
}
