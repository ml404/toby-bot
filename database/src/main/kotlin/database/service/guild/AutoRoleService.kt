package database.service.guild

import database.dto.guild.AutoRoleDto

interface AutoRoleService {
    fun listForGuild(guildId: Long): List<AutoRoleDto>
    fun add(guildId: Long, roleId: Long): AutoRoleDto
    fun delete(guildId: Long, roleId: Long)
}
