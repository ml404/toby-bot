package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.ICommand;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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

    public void queue(AudioTrack track, long startPosition) {
        track.setPosition(startPosition);
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
                event.replyFormat("Setting volume back to '%d' \uD83D\uDD0A", previousVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
            nextTrack();
            nowPlaying(event, player.getPlayingTrack(), deleteDelay);
        }
    }

//    @Override
//    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
////        if(track.getPosition() == 0L) {
////            getCurrentTextChannel().sendMessage(String.format("Track %s got stuck, skipping.", track.getInfo().title).queue(message -> ICommand.deleteAfter(message, deleteDelay));
////            nextTrack();
////        }
//    }

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
