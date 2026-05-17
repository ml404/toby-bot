package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "TeamPresetDto.getByGuild",
        query = "select t from TeamPresetDto t WHERE t.guildId = :guildId ORDER BY LOWER(t.name) ASC"
    ),
    NamedQuery(
        name = "TeamPresetDto.getByGuildAndName",
        query = "select t from TeamPresetDto t WHERE t.guildId = :guildId AND LOWER(t.name) = LOWER(:name)"
    ),
    NamedQuery(
        name = "TeamPresetDto.getById",
        query = "select t from TeamPresetDto t WHERE t.id = :id"
    ),
    NamedQuery(
        name = "TeamPresetDto.deleteById",
        query = "delete from TeamPresetDto t WHERE t.id = :id"
    ),
    NamedQuery(
        name = "TeamPresetDto.deleteAllByGuild",
        query = "delete from TeamPresetDto t WHERE t.guildId = :guildId"
    ),
)
@Entity
@Table(name = "team_preset", schema = "public")
@Transactional
class TeamPresetDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id")
    var guildId: Long = 0L,

    @Column(name = "name")
    var name: String = "",

    @Column(name = "member_ids")
    var memberIds: String = "",

    @Column(name = "created_by_discord_id")
    var createdByDiscordId: Long = 0L,

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
) : Serializable {

    /** CSV ↔ list helper. Blank strings yield an empty list (not [""]) so
     *  callers don't have to filter on the way out. */
    var memberIdList: List<Long>
        get() = memberIds.split(',').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toLongOrNull() }
        set(value) {
            memberIds = value.joinToString(",")
        }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == null) createdAt = now
        if (updatedAt == null) updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
