package bot.toby.lavaplayer

import bot.toby.intro.IntroLoudnessMeasuredEvent
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager

class GuildMusicManager(manager: AudioPlayerManager, idLong: Long = 0) {
    val guildId: Long = idLong
    val audioPlayer: AudioPlayer = manager.createPlayer()
    val scheduler: TrackScheduler = TrackScheduler(this.audioPlayer, idLong)
    val sendHandler: AudioPlayerSendHandler

    init {
        audioPlayer.addListener(this.scheduler)
        // Measures how loud each intro actually is so its volume can be
        // corrected on later plays. The factory builds an empty chain for
        // everything that isn't an intro, so regular music playback is
        // untouched.
        audioPlayer.setFilterFactory(
            IntroLoudnessFilterFactory(
                introIdOf = { track -> scheduler.introIdFor(track) },
                onMeasured = { introId, rms -> SchedulerEvents.publish(IntroLoudnessMeasuredEvent(introId, rms)) },
            ),
        )
        this.sendHandler = AudioPlayerSendHandler(this.audioPlayer)
    }
}
