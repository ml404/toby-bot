package bot.toby.scheduling

import common.logging.DiscordLogger
import database.service.user.SharedCubeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Ages out published cube and deal snapshots.
 *
 * A share link is immutable and nothing ever removed one, so the table only
 * ever grew — every Share click was another permanent row holding up to
 * 100,000 characters of card text. A link is meant to outlive the draft it
 * was made for, though, so the window is deliberately long: a year-old link
 * is one nobody is coming back to, while a month-old one might still be
 * pinned in someone's channel.
 *
 * The per-user cap in the web layer bounds how fast the table can grow; this
 * bounds how long it stays grown.
 */
@Component
@Profile("prod")
class SharedCubeRetentionJob @Autowired constructor(
    private val sharedCubes: SharedCubeService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    /** Runs nightly, offset from the other retention sweep rather than alongside it. */
    @Scheduled(cron = "0 45 3 * * *", zone = "UTC")
    fun purgeStaleSnapshots() {
        runCatching {
            val cutoff = clock.instant().minus(RETENTION)
            val deleted = sharedCubes.purge(cutoff)
            if (deleted > 0) logger.info { "Purged $deleted shared_cube rows created before $cutoff." }
        }.onFailure { logger.error("Shared cube retention purge failed: ${it.message}") }
    }

    companion object {
        /** How long a published share link is kept. */
        val RETENTION: Duration = Duration.ofDays(365)
    }
}
