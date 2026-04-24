package bot.toby.activity

import common.logging.DiscordLogger
import database.service.ActivitySessionService
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ActivitySessionRecoveryHook @Autowired constructor(
    private val activitySessionService: ActivitySessionService,
    private val activityTrackingService: ActivityTrackingService
) : InitializingBean {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun afterPropertiesSet() {
        closeStaleSessions()
    }

    fun closeStaleSessions() {
        val now = Instant.now()
        val open = runCatching { activitySessionService.findAllOpen() }
            .onFailure { logger.error("Could not query open activity sessions: ${it.message}") }
            .getOrDefault(emptyList())

        if (open.isEmpty()) {
            logger.info { "No stale activity sessions to recover." }
            return
        }

        logger.info { "Recovering ${open.size} stale activity session(s)." }
        open.forEach { session ->
            runCatching { activityTrackingService.closeRecoveredSession(session, now) }
                .onFailure { logger.error("Failed to close stale activity session ${session.id}: ${it.message}") }
        }
    }
}
