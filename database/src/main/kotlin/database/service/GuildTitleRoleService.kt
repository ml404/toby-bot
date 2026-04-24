package database.service

import database.dto.GuildTitleRoleDto

interface GuildTitleRoleService {
    fun get(guildId: Long, titleId: Long): GuildTitleRoleDto?
    fun listByGuild(guildId: Long): List<GuildTitleRoleDto>
    fun save(dto: GuildTitleRoleDto): GuildTitleRoleDto
    fun delete(guildId: Long, titleId: Long)
}
