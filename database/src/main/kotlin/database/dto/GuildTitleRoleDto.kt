package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(
        name = "GuildTitleRoleDto.get",
        query = "select r from GuildTitleRoleDto r where r.guildId = :guildId and r.titleId = :titleId"
    ),
    NamedQuery(
        name = "GuildTitleRoleDto.getByGuild",
        query = "select r from GuildTitleRoleDto r where r.guildId = :guildId"
    )
)
@Entity
@Table(name = "guild_title_role", schema = "public")
@IdClass(GuildTitleRoleId::class)
@Transactional
class GuildTitleRoleDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "title_id")
    var titleId: Long = 0,

    @Column(name = "discord_role_id", nullable = false)
    var discordRoleId: Long = 0
) : Serializable

data class GuildTitleRoleId(
    var guildId: Long = 0,
    var titleId: Long = 0
) : Serializable
