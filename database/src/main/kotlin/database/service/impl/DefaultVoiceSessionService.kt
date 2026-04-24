package database.service.impl

import database.dto.VoiceSessionDto
import database.persistence.VoiceSessionPersistence
import database.service.VoiceSessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultVoiceSessionService @Autowired constructor(
    private val persistence: VoiceSessionPersistence
) : VoiceSessionService {

    override fun openSession(session: VoiceSessionDto): VoiceSessionDto = persistence.openSession(session)

    override fun findOpenSession(discordId: Long, guildId: Long): VoiceSessionDto? =
        persistence.findOpenSession(discordId, guildId)

    override fun findAllOpenSessions(): List<VoiceSessionDto> = persistence.findAllOpenSessions()

    override fun closeSession(session: VoiceSessionDto): VoiceSessionDto = persistence.closeSession(session)

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
}
