package database.dto.guild

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(
        name = "AutoRoleDto.getGuildAll",
        query = "select r from AutoRoleDto r where r.guildId = :guildId order by r.roleId asc"
    ),
    NamedQuery(
        name = "AutoRoleDto.getByGuildAndRole",
        query = "select r from AutoRoleDto r where r.guildId = :guildId and r.roleId = :roleId"
    )
)
@Entity
@Table(name = "auto_role", schema = "public")
@IdClass(AutoRoleId::class)
@Transactional
class AutoRoleDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "role_id")
    var roleId: Long = 0
) : Serializable

data class AutoRoleId(
    var guildId: Long = 0,
    var roleId: Long = 0
) : Serializable
