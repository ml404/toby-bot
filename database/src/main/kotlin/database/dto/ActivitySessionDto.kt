package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "ActivitySessionDto.findOpen",
        query = "select s from ActivitySessionDto s " +
                "where s.discordId = :discordId and s.guildId = :guildId and s.endedAt is null"
    ),
    NamedQuery(
        name = "ActivitySessionDto.findAllOpen",
        query = "select s from ActivitySessionDto s where s.endedAt is null"
    ),
    NamedQuery(
        name = "ActivitySessionDto.findClosedBefore",
        query = "select s from ActivitySessionDto s where s.endedAt is not null and s.startedAt < :cutoff"
    ),
    NamedQuery(
        name = "ActivitySessionDto.deleteClosedBefore",
        query = "delete from ActivitySessionDto s where s.endedAt is not null and s.startedAt < :cutoff"
    )
)
@Entity
@Table(name = "activity_session", schema = "public")
@Transactional
class ActivitySessionDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "discord_id", nullable = false)
    var discordId: Long = 0,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "activity_name", nullable = false)
    var activityName: String = "",

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "ended_at")
    var endedAt: Instant? = null
) : Serializable
