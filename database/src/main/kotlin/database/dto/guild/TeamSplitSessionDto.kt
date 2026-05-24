package database.dto.guild

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@NamedQueries(
    NamedQuery(
        name = "TeamSplitSessionDto.getById",
        query = "select s from TeamSplitSessionDto s WHERE s.id = :id"
    ),
    NamedQuery(
        name = "TeamSplitSessionDto.deleteById",
        query = "delete from TeamSplitSessionDto s WHERE s.id = :id"
    ),
    NamedQuery(
        name = "TeamSplitSessionDto.deleteOlderThan",
        query = "delete from TeamSplitSessionDto s WHERE s.createdAt < :cutoff"
    ),
    NamedQuery(
        name = "TeamSplitSessionDto.recentForGuild",
        query = "select s from TeamSplitSessionDto s WHERE s.guildId = :guildId ORDER BY s.createdAt DESC"
    ),
)
@Entity
@Table(name = "team_split_session", schema = "public")
@Transactional
class TeamSplitSessionDto(
    @Id
    @Column(name = "id")
    var id: UUID = UUID.randomUUID(),

    @Column(name = "guild_id")
    var guildId: Long = 0L,

    @Column(name = "requester_discord_id")
    var requesterDiscordId: Long = 0L,

    /** CSV of Discord snowflakes — the source roster for the split. */
    @Column(name = "member_ids")
    var memberIds: String = "",

    @Column(name = "team_count")
    var teamCount: Int = 2,

    /**
     * Pipe-delimited groups of CSV ids: `1,2,3|4,5|6,7`. Lined up with
     * [teamNames] by index. Plain text rather than JSON to keep the
     * database module free of a Jackson dependency.
     */
    @Column(name = "assignments")
    var assignments: String = "",

    /** Newline-delimited (team names may contain commas). One per team. */
    @Column(name = "team_names")
    var teamNames: String = "",

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "last_action")
    var lastAction: String = ACTION_CREATED,
) : Serializable {

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }

    companion object {
        const val ACTION_CREATED = "created"
        const val ACTION_REROLLED = "rerolled"
        const val ACTION_CONFIRMED = "confirmed"
        const val ACTION_CANCELLED = "cancelled"
    }
}
