package database.persistence

import database.dto.VoiceSessionDto
import java.time.Instant

interface VoiceSessionPersistence {
    fun openSession(session: VoiceSessionDto): VoiceSessionDto
    fun findOpenSession(discordId: Long, guildId: Long): VoiceSessionDto?
    fun findAllOpenSessions(): List<VoiceSessionDto>
    fun closeSession(session: VoiceSessionDto): VoiceSessionDto
    fun sumCountedSecondsInRange(guildId: Long, discordId: Long, from: Instant, until: Instant): Long
    fun sumCountedSecondsInRangeByUser(guildId: Long, from: Instant, until: Instant): Map<Long, Long>
    fun sumCountedSecondsLifetimeByUser(guildId: Long): Map<Long, Long>

    /**
     * Closed sessions for [guildId] that overlap the [from, until) window
     * — i.e. `joinedAt < until AND leftAt >= from`. Used by the moderation
     * Activity tab to split each session across the calendar days it
     * actually touched (a session 23:50→00:10 splits 10 minutes into each
     * day).
     */
    fun findClosedOverlapping(guildId: Long, from: Instant, until: Instant): List<VoiceSessionDto>
}
