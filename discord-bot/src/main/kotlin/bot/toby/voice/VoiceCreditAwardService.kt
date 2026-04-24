package bot.toby.voice

import common.logging.DiscordLogger
import database.dto.VoiceCreditDailyDto
import database.dto.VoiceSessionDto
import database.service.UserService
import database.service.VoiceCreditDailyService
import database.service.VoiceSessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class VoiceCreditAwardService @Autowired constructor(
    private val voiceSessionService: VoiceSessionService,
    private val voiceCreditDailyService: VoiceCreditDailyService,
    private val userService: UserService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun closeSessionAndAward(
        session: VoiceSessionDto,
        closedAt: Instant,
        hadCompanyDurationSeconds: Long
    ) {
        val rawSeconds = Duration.between(session.joinedAt, closedAt).seconds.coerceAtLeast(0)
        val countedSeconds = hadCompanyDurationSeconds.coerceIn(0, rawSeconds)
        val rawCredits = countedSeconds / VoiceCreditConfig.SECONDS_PER_CREDIT
        val awardedCredits = clampToDailyCap(session.discordId, session.guildId, rawCredits, closedAt)

        session.leftAt = closedAt
        session.countedSeconds = countedSeconds
        session.creditsAwarded = awardedCredits
        voiceSessionService.closeSession(session)

        if (awardedCredits > 0) {
            val user = userService.getUserById(session.discordId, session.guildId)
            if (user != null) {
                user.socialCredit = (user.socialCredit ?: 0L) + awardedCredits
                userService.updateUser(user)
                logger.info {
                    "Awarded $awardedCredits voice credits to user ${session.discordId} in guild ${session.guildId} " +
                            "(counted=${countedSeconds}s, raw=${rawSeconds}s)"
                }
            } else {
                logger.warn(
                    "No user row for discordId=${session.discordId} guildId=${session.guildId}; skipped awarding"
                )
            }
        }
    }

    fun closeRecoveredSession(session: VoiceSessionDto, closedAt: Instant) {
        val rawSeconds = Duration.between(session.joinedAt, closedAt).seconds.coerceAtLeast(0)
        val cappedSeconds = rawSeconds.coerceAtMost(VoiceCreditConfig.MAX_RECOVERED_SESSION_SECONDS)
        closeSessionAndAward(session, closedAt, cappedSeconds)
    }

    private fun clampToDailyCap(discordId: Long, guildId: Long, newCredits: Long, at: Instant): Long {
        if (newCredits <= 0) return 0L
        val today = LocalDate.ofInstant(at, ZoneOffset.UTC)
        val existing = voiceCreditDailyService.get(discordId, guildId, today)
        val usedToday = existing?.credits ?: 0L
        val headroom = (VoiceCreditConfig.DAILY_CREDIT_CAP - usedToday).coerceAtLeast(0L)
        val granted = newCredits.coerceAtMost(headroom)
        if (granted > 0) {
            voiceCreditDailyService.upsert(
                VoiceCreditDailyDto(
                    discordId = discordId,
                    guildId = guildId,
                    earnDate = today,
                    credits = usedToday + granted
                )
            )
        }
        return granted
    }
}
