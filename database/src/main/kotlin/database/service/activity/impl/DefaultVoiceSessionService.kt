package database.service.activity.impl

import common.events.VoiceSessionLoggedEvent
import database.dto.VoiceSessionDto
import database.persistence.activity.VoiceSessionPersistence
import database.service.activity.VoiceSessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultVoiceSessionService @Autowired constructor(
    private val persistence: VoiceSessionPersistence,
    private val eventPublisher: ApplicationEventPublisher? = null,
) : VoiceSessionService {

    override fun openSession(session: VoiceSessionDto): VoiceSessionDto = persistence.openSession(session)

    override fun findOpenSession(discordId: Long, guildId: Long): VoiceSessionDto? =
        persistence.findOpenSession(discordId, guildId)

    override fun findAllOpenSessions(): List<VoiceSessionDto> = persistence.findAllOpenSessions()

    override fun closeSession(session: VoiceSessionDto): VoiceSessionDto {
        val closed = persistence.closeSession(session)
        val seconds = closed.countedSeconds ?: 0L
        if (seconds > 0L) {
            eventPublisher?.publishEvent(
                VoiceSessionLoggedEvent(
                    discordId = closed.discordId,
                    guildId = closed.guildId,
                    countedSeconds = seconds
                )
            )
        }
        return closed
    }

    override fun sumCountedSecondsInRange(
        guildId: Long,
        discordId: Long,
        from: Instant,
        until: Instant
    ): Long = persistence.sumCountedSecondsInRange(guildId, discordId, from, until)

    override fun sumCountedSecondsInRangeByUser(
        guildId: Long,
        from: Instant,
        until: Instant
    ): Map<Long, Long> = persistence.sumCountedSecondsInRangeByUser(guildId, from, until)

    override fun sumCountedSecondsLifetimeByUser(guildId: Long): Map<Long, Long> =
        persistence.sumCountedSecondsLifetimeByUser(guildId)

    override fun findClosedOverlapping(
        guildId: Long,
        from: Instant,
        until: Instant
    ): List<VoiceSessionDto> = persistence.findClosedOverlapping(guildId, from, until)
}
