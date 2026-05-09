package bot.toby.music

import bot.toby.helpers.MusicPlayerHelper
import bot.toby.managers.NowPlayingManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * MusicPlayerHelper is still a Kotlin object (commands and TrackScheduler
 * call into it via static imports), but its embedded NowPlayingManager
 * needs to be injectable so handlers can depend on the same instance
 * without reaching into the helper as a hidden dependency.
 */
@Configuration
class MusicBeans {

    @Bean
    fun nowPlayingManager(): NowPlayingManager = MusicPlayerHelper.nowPlayingManager
}
