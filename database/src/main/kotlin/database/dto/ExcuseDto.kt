package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "ExcuseDto.getAll",
        query = "select e from ExcuseDto e WHERE e.guildId = :guildId ORDER BY e.createdAt DESC"
    ),
    NamedQuery(
        name = "ExcuseDto.getApproved",
        query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = true ORDER BY e.createdAt DESC"
    ),
    NamedQuery(
        name = "ExcuseDto.getPending",
        query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = false ORDER BY e.createdAt DESC"
    ),
    NamedQuery(
        name = "ExcuseDto.searchApproved",
        query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = true AND LOWER(e.excuse) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY e.createdAt DESC"
    ),
    NamedQuery(
        name = "ExcuseDto.countApproved",
        query = "select count(e) from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = true"
    ),
    NamedQuery(
        name = "ExcuseDto.countPending",
        query = "select count(e) from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = false"
    ),
    NamedQuery(name = "ExcuseDto.getById", query = "select e from ExcuseDto e WHERE e.id = :id"),
    NamedQuery(name = "ExcuseDto.deleteById", query = "delete from ExcuseDto e WHERE e.id = :id"),
    NamedQuery(name = "ExcuseDto.deleteAllByGuildId", query = "delete from ExcuseDto e WHERE e.guildId = :guildId")
)
@Entity
@Table(name = "excuse", schema = "public")
@Transactional
class ExcuseDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id")
    var guildId: Long? = 0L,

    @Column(name = "author")
    var author: String? = null,

    @Column(name = "excuse")
    var excuse: String? = null,

    @Column(name = "approved")
    var approved: Boolean = false,

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "approved_at")
    var approvedAt: Instant? = null,

    @Column(name = "author_discord_id")
    var authorDiscordId: Long? = null
) : Serializable {

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}
