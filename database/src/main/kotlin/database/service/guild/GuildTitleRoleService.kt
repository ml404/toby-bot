package database.service.guild

import database.dto.guild.GuildTitleRoleDto

interface GuildTitleRoleService {
    fun get(guildId: Long, titleId: Long): GuildTitleRoleDto?
    fun listByGuild(guildId: Long): List<GuildTitleRoleDto>
    fun save(dto: GuildTitleRoleDto): GuildTitleRoleDto
    fun delete(guildId: Long, titleId: Long)
}
