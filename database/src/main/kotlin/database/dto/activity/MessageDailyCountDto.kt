package database.dto.activity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * Per-guild per-day message counter feeding the moderation Activity tab.
 * One row per `(guildId, dayStart)`; the counter is incremented by
 * `MessageActivityBuffer` in batches (in-memory accumulation flushed
 * once a minute) so individual messages don't round-trip to Postgres.
 *
 * Composite primary key matches the natural shape of the data and lets
 * the 30-day window query do an index-only scan.
 */
@NamedQueries(
    NamedQuery(
        name = "MessageDailyCountDto.findByGuildSince",
        query = "select m from MessageDailyCountDto m " +
                "where m.guildId = :guildId and m.dayStart >= :since " +
                "order by m.dayStart asc"
    ),
    NamedQuery(
        name = "MessageDailyCountDto.findByGuildAndDay",
        query = "select m from MessageDailyCountDto m " +
                "where m.guildId = :guildId and m.dayStart = :dayStart"
    ),
    NamedQuery(
        name = "MessageDailyCountDto.lastActiveByGuild",
        query = "select m.guildId, max(m.dayStart) from MessageDailyCountDto m group by m.guildId"
    ),
)
@Entity
@Table(name = "message_daily_count", schema = "public")
@IdClass(MessageDailyCountId::class)
@Transactional
class MessageDailyCountDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "day_start")
    var dayStart: LocalDate = LocalDate.now(),

    @Column(name = "count", nullable = false)
    var count: Long = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) : Serializable

data class MessageDailyCountId(
    var guildId: Long = 0,
    var dayStart: LocalDate = LocalDate.now(),
) : Serializable
