package database.service

import database.dto.VoiceSessionDto
import java.time.Instant

interface VoiceSessionService {
    fun openSession(session: VoiceSessionDto): VoiceSessionDto
    fun findOpenSession(discordId: Long, guildId: Long): VoiceSessionDto?
    fun findAllOpenSessions(): List<VoiceSessionDto>
    fun closeSession(session: VoiceSessionDto): VoiceSessionDto
    fun sumCountedSecondsInRange(guildId: Long, discordId: Long, from: Instant, until: Instant): Long
    fun sumCountedSecondsInRangeByUser(guildId: Long, from: Instant, until: Instant): Map<Long, Long>
    fun sumCountedSecondsLifetimeByUser(guildId: Long): Map<Long, Long>
}
