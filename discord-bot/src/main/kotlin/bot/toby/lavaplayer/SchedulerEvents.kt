package bot.toby.lavaplayer

import org.springframework.context.ApplicationEventPublisher

/**
 * TrackScheduler / GuildMusicManager / PlayerManager are not Spring beans (they're constructed
 * by the PlayerManager singleton), so this static hook bridges them to the Spring event bus.
 *
 * A @Configuration in the application module sets `publisher` after ApplicationReadyEvent
 * fires. Until then, calls to [publish] are silent no-ops, which keeps unit tests of
 * TrackScheduler free of Spring wiring.
 */
object SchedulerEvents {
    @Volatile
    var publisher: ApplicationEventPublisher? = null

    fun publish(event: Any) {
        publisher?.publishEvent(event)
    }
}
