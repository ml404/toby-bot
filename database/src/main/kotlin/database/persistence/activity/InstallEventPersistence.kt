package database.persistence.activity

import database.dto.activity.InstallEventDto
import database.dto.activity.InstallEventType
import java.time.Instant

/**
 * Append + aggregate plumbing for the install lifecycle ledger. Writes
 * are pure inserts (the log is append-only); reads are coarse aggregates
 * for the operator dashboard — never per-guild hot-path lookups.
 */
interface InstallEventPersistence {
    /** Append a single [type] event for [guildId] at [occurredAt]. */
    fun record(guildId: Long, type: InstallEventType, occurredAt: Instant)

    /** Lifetime count of events of [type]. */
    fun countByType(type: InstallEventType): Long

    /** Count of [type] events at or after [since]. */
    fun countByTypeSince(type: InstallEventType, since: Instant): Long

    /** Every event at or after [since], ascending — for time-bucketed charts. */
    fun findSince(since: Instant): List<InstallEventDto>
}
