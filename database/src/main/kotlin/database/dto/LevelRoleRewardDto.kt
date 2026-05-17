package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(
        name = "LevelRoleRewardDto.getGuildAll",
        query = "select r from LevelRoleRewardDto r where r.guildId = :guildId order by r.level asc"
    ),
    NamedQuery(
        name = "LevelRoleRewardDto.getByGuildAndLevel",
        query = "select r from LevelRoleRewardDto r where r.guildId = :guildId and r.level = :level"
    ),
    NamedQuery(
        name = "LevelRoleRewardDto.getInRange",
        query = "select r from LevelRoleRewardDto r where r.guildId = :guildId " +
                "and r.level > :fromExclusive and r.level <= :toInclusive order by r.level asc"
    )
)
@Entity
@Table(name = "level_role_reward", schema = "public")
@IdClass(LevelRoleRewardId::class)
@Transactional
class LevelRoleRewardDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "level")
    var level: Int = 0,

    @Column(name = "role_id", nullable = false)
    var roleId: Long = 0
) : Serializable

data class LevelRoleRewardId(
    var guildId: Long = 0,
    var level: Int = 0
) : Serializable
