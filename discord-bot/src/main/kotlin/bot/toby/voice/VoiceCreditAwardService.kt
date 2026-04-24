package bot.toby.voice

import common.logging.DiscordLogger
import database.dto.VoiceSessionDto
import database.service.SocialCreditAwardService
import database.service.VoiceSessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class VoiceCreditAwardService @Autowired constructor(
    private val voiceSessionService: VoiceSessionService,
    private val awardService: SocialCreditAwardService
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

        val awardedCredits = awardService.award(
            discordId = session.discordId,
            guildId = session.guildId,
            amount = rawCredits,
            reason = "voice-session",
            at = closedAt,
            dailyCap = VoiceCreditConfig.DAILY_CREDIT_CAP
        )

        session.leftAt = closedAt
        session.countedSeconds = countedSeconds
        session.creditsAwarded = awardedCredits
        voiceSessionService.closeSession(session)

        if (rawCredits > 0 && awardedCredits == 0L) {
            logger.warn(
                "No user row for discordId=${session.discordId} guildId=${session.guildId}; skipped awarding"
            )
        } else if (awardedCredits > 0) {
            logger.info {
                "Awarded $awardedCredits voice credits to user ${session.discordId} in guild ${session.guildId} " +
                        "(counted=${countedSeconds}s, raw=${rawSeconds}s)"
            }
        }
    }

    fun closeRecoveredSession(session: VoiceSessionDto, closedAt: Instant) {
        val rawSeconds = Duration.between(session.joinedAt, closedAt).seconds.coerceAtLeast(0)
        val cappedSeconds = rawSeconds.coerceAtMost(VoiceCreditConfig.MAX_RECOVERED_SESSION_SECONDS)
        closeSessionAndAward(session, closedAt, cappedSeconds)
    }

    /**
     * Close a still-open session because the bot is shutting down cleanly.
     * The user's session was live right up to this moment, so we treat the
     * entire duration as counted — no 8h recovery cap, since a session can't
     * outlive the uptime that spawned it. Stopping the bot here means:
     *
     *   - `leftAt` reflects the actual shutdown time (not whenever the bot
     *     wakes up next, which can be hours later and would inflate credit)
     *   - Credits get awarded immediately rather than deferred to startup
     *   - On the next boot, [VoiceSessionRecoveryHook] sees nothing to
     *     recover for this user, so no over-count path exists for clean
     *     deploys. Crash shutdowns still fall through to recovery.
     */
    fun closeSessionAtShutdown(session: VoiceSessionDto, closedAt: Instant) {
        val rawSeconds = Duration.between(session.joinedAt, closedAt).seconds.coerceAtLeast(0)
        closeSessionAndAward(session, closedAt, rawSeconds)
    }
}
