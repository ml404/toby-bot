package database.service.activity

import database.dto.activity.InstallEventDto
import database.dto.activity.InstallEventType
import java.time.Instant

/**
 * Records and aggregates bot install lifecycle events (JOIN / LEAVE).
 * Recorded from the JDA event handlers; aggregated by the operator
 * `/admin/installs` dashboard for churn + growth metrics.
 */
interface InstallEventService {
    /** Append a JOIN for [guildId] (defaults to now). */
    fun recordJoin(guildId: Long, occurredAt: Instant = Instant.now())

    /** Append a LEAVE for [guildId] (defaults to now). */
    fun recordLeave(guildId: Long, occurredAt: Instant = Instant.now())

    fun countByType(type: InstallEventType): Long
    fun countByTypeSince(type: InstallEventType, since: Instant): Long
    fun findSince(since: Instant): List<InstallEventDto>
}
