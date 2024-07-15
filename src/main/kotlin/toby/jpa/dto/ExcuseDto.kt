package toby.jpa.dto

import jakarta.persistence.*
import org.apache.commons.lang3.builder.EqualsBuilder
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "ExcuseDto.getAll", query = "select e from ExcuseDto e WHERE e.guildId = :guildId"),
    NamedQuery(name = "ExcuseDto.getApproved", query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = true"),
    NamedQuery(name = "ExcuseDto.getPending", query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = false"),
    NamedQuery(name = "ExcuseDto.getById", query = "select e from ExcuseDto e WHERE e.id = :id"),
    NamedQuery(name = "ExcuseDto.deleteById", query = "delete from ExcuseDto e WHERE e.id = :id"),
    NamedQuery(name = "ExcuseDto.deleteAllByGuildId", query = "delete from ExcuseDto e WHERE e.guildId = :guildId")
)
@Entity
@Table(name = "excuse", schema = "public")
@Transactional
data class ExcuseDto(
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
    var approved: Boolean = false
) : Serializable
