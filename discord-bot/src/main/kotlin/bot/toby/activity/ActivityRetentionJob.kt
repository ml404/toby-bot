package bot.toby.activity

import common.logging.DiscordLogger
import database.service.ActivityMonthlyRollupService
import database.service.ActivitySessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Component
class ActivityRetentionJob @Autowired constructor(
    private val activitySessionService: ActivitySessionService,
    private val activityMonthlyRollupService: ActivityMonthlyRollupService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    companion object {
        const val RAW_SESSION_RETENTION_DAYS: Long = 30L
        const val ROLLUP_RETENTION_MONTHS: Long = 12L
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    fun purgeStaleRows() {
        runCatching {
            val sessionCutoff = Instant.now().minusSeconds(RAW_SESSION_RETENTION_DAYS * 24L * 3600L)
            val deleted = activitySessionService.deleteClosedBefore(sessionCutoff)
            if (deleted > 0) logger.info { "Purged $deleted activity_session rows older than $RAW_SESSION_RETENTION_DAYS days." }
        }.onFailure { logger.error("Session retention purge failed: ${it.message}") }

        runCatching {
            val rollupCutoff = LocalDate.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .minusMonths(ROLLUP_RETENTION_MONTHS)
            val deleted = activityMonthlyRollupService.deleteBefore(rollupCutoff)
            if (deleted > 0) logger.info { "Purged $deleted activity_monthly_rollup rows older than $rollupCutoff." }
        }.onFailure { logger.error("Rollup retention purge failed: ${it.message}") }
    }
}
