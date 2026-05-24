package bot.toby.voice

import common.logging.DiscordLogger
import database.dto.VoiceSessionDto
import database.service.activity.VoiceSessionService
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class VoiceSessionLifecycle(
    private val voiceSessionService: VoiceSessionService,
    private val voiceCompanyTracker: VoiceCompanyTracker,
    private val voiceCreditAwardService: VoiceCreditAwardService,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun openSession(userId: Long, guildId: Long, channel: AudioChannel, now: Instant) {
        runCatching {
            voiceSessionService.findOpenSession(userId, guildId)?.let { stale ->
                val companySeconds = voiceCompanyTracker.stopTracking(userId, guildId, now)
                voiceCreditAwardService.closeSessionAndAward(stale, now, companySeconds)
            }
            voiceCompanyTracker.reconcileChannel(channel, now)
            val session = VoiceSessionDto(
                discordId = userId,
                guildId = guildId,
                channelId = channel.idLong,
                joinedAt = now,
            )
            voiceSessionService.openSession(session)
            voiceCompanyTracker.startTracking(userId, guildId, channel, now)
        }.onFailure { logger.error("Failed to open voice session for user $userId: ${it.message}") }
    }

    fun closeSession(userId: Long, guildId: Long, leftChannel: AudioChannel?, now: Instant) {
        runCatching {
            val open = voiceSessionService.findOpenSession(userId, guildId)
            if (open != null) {
                val companySeconds = voiceCompanyTracker.stopTracking(userId, guildId, now)
                voiceCreditAwardService.closeSessionAndAward(open, now, companySeconds)
            }
            // Other members still in the channel may have just lost company —
            // refresh their accumulators regardless of whether the leaver had
            // an open session.
            leftChannel?.let { voiceCompanyTracker.reconcileChannel(it, now) }
        }.onFailure { logger.error("Failed to close voice session for user $userId: ${it.message}") }
    }
}
