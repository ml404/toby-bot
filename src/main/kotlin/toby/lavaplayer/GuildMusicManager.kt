package toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager

class GuildMusicManager(manager: AudioPlayerManager) {
    @JvmField
    val audioPlayer: AudioPlayer = manager.createPlayer()
    @JvmField
    val scheduler: TrackScheduler = TrackScheduler(this.audioPlayer)
    @JvmField
    val sendHandler: AudioPlayerSendHandler

    fun getAudioPlayer() = audioPlayer
    fun getScheduler() = scheduler
    fun getSendHandler() = sendHandler

    init {
        audioPlayer.addListener(this.scheduler)
        this.sendHandler = AudioPlayerSendHandler(this.audioPlayer)
    }
}
