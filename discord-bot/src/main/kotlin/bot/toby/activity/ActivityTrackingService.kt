package bot.toby.activity

import common.logging.DiscordLogger
import database.dto.ActivitySessionDto
import database.dto.ConfigDto.Configurations.ACTIVITY_TRACKING
import database.service.ActivityMonthlyRollupService
import database.service.ActivitySessionService
import database.service.ConfigService
import database.service.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@Service
class ActivityTrackingService @Autowired constructor(
    private val activitySessionService: ActivitySessionService,
    private val activityMonthlyRollupService: ActivityMonthlyRollupService,
    private val userService: UserService,
    private val configService: ConfigService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    companion object {
        const val MIN_SESSION_SECONDS: Long = 60L
        const val MAX_RECOVERED_SESSION_SECONDS: Long = 8L * 60L * 60L
    }

    fun isTrackingAllowed(discordId: Long, guildId: Long): Boolean {
        if (!isGuildTrackingEnabled(guildId)) return false
        val user = userService.getUserById(discordId, guildId) ?: return true
        return !user.activityTrackingOptOut
    }

    fun isGuildTrackingEnabled(guildId: Long): Boolean {
        val cfg = configService.getConfigByName(ACTIVITY_TRACKING.configValue, guildId.toString())
        return cfg?.value?.equals("true", ignoreCase = true) == true
    }

    fun openSession(discordId: Long, guildId: Long, activityName: String, at: Instant) {
        if (activityName.isBlank()) return
        if (!isTrackingAllowed(discordId, guildId)) return
        runCatching {
            activitySessionService.findOpen(discordId, guildId)?.let {
                closeSession(it, at)
            }
            activitySessionService.openSession(
                ActivitySessionDto(
                    discordId = discordId,
                    guildId = guildId,
                    activityName = activityName,
                    startedAt = at
                )
            )
        }.onFailure {
            logger.error("Could not open activity session for $discordId: ${it.message}")
        }
    }

    fun closeOpenSessionForUser(discordId: Long, guildId: Long, at: Instant) {
        runCatching {
            activitySessionService.findOpen(discordId, guildId)?.let { closeSession(it, at) }
        }.onFailure {
            logger.error("Could not close activity session for $discordId: ${it.message}")
        }
    }

    fun closeSession(session: ActivitySessionDto, at: Instant) {
        val endedAt = at.coerceAtLeast(session.startedAt)
        session.endedAt = endedAt
        activitySessionService.closeSession(session)

        val seconds = Duration.between(session.startedAt, endedAt).seconds
        if (seconds >= MIN_SESSION_SECONDS) {
            val monthStart = session.startedAt.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
            activityMonthlyRollupService.addSeconds(
                discordId = session.discordId,
                guildId = session.guildId,
                monthStart = monthStart,
                activityName = session.activityName,
                delta = seconds
            )
        }
    }

    fun closeRecoveredSession(session: ActivitySessionDto, at: Instant) {
        val raw = Duration.between(session.startedAt, at).seconds.coerceAtLeast(0)
        val cappedEnd = if (raw > MAX_RECOVERED_SESSION_SECONDS) {
            session.startedAt.plusSeconds(MAX_RECOVERED_SESSION_SECONDS)
        } else {
            at
        }
        closeSession(session, cappedEnd)
    }
}
