package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager

class GuildMusicManager(manager: AudioPlayerManager, idLong: Long = 0) {
    val guildId: Long = idLong
    val audioPlayer: AudioPlayer = manager.createPlayer()
    val scheduler: TrackScheduler = TrackScheduler(this.audioPlayer, idLong)
    val sendHandler: AudioPlayerSendHandler

    init {
        audioPlayer.addListener(this.scheduler)
        this.sendHandler = AudioPlayerSendHandler(this.audioPlayer)
    }
}
