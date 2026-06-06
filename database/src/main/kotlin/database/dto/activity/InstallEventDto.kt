package database.dto.activity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

/** The two install lifecycle events the operator dashboard tracks. */
enum class InstallEventType { JOIN, LEAVE }

/**
 * Append-only row in the install lifecycle ledger (one per bot
 * join/leave). Backed by `install_event` (V48). The surrogate
 * `BIGSERIAL` id keeps the log append-only — a guild can legitimately
 * join, leave, and re-join, so there's no natural composite key to
 * dedupe on (and we don't want to: each transition is a distinct event).
 *
 * Read paths aggregate by [eventType] and [occurredAt]; see
 * [database.persistence.activity.InstallEventPersistence].
 */
@NamedQueries(
    NamedQuery(
        name = "InstallEventDto.countByType",
        query = "select count(e) from InstallEventDto e where e.eventType = :eventType"
    ),
    NamedQuery(
        name = "InstallEventDto.countByTypeSince",
        query = "select count(e) from InstallEventDto e " +
                "where e.eventType = :eventType and e.occurredAt >= :since"
    ),
    NamedQuery(
        name = "InstallEventDto.findSince",
        query = "select e from InstallEventDto e where e.occurredAt >= :since order by e.occurredAt asc"
    ),
)
@Entity
@Table(name = "install_event", schema = "public")
@Transactional
class InstallEventDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "event_type", nullable = false, length = 8)
    var eventType: String = InstallEventType.JOIN.name,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now(),
) : Serializable
