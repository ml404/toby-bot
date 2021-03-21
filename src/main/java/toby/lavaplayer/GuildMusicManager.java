package toby.lavaplayer;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;

public class GuildMusicManager {
    private final AudioPlayer audioPlayer;
    private final TrackScheduler scheduler;
    private final AudioPlayerSendHandler sendHandler;


    public GuildMusicManager(AudioPlayerManager manager) {
        this.audioPlayer = manager.createPlayer();
        this.scheduler = new TrackScheduler(this.getAudioPlayer());
        this.getAudioPlayer().addListener(this.getScheduler());
        this.sendHandler = new AudioPlayerSendHandler(this.getAudioPlayer());
    }

    public AudioPlayerSendHandler getSendHandler(){
        return sendHandler;
    }

    public AudioPlayer getAudioPlayer() {
        return audioPlayer;
    }

    public TrackScheduler getScheduler() {
        return scheduler;
    }
}
