package database.persistence

import database.dto.AutoRoleDto

interface AutoRolePersistence {
    fun listForGuild(guildId: Long): List<AutoRoleDto>
    fun exists(guildId: Long, roleId: Long): Boolean
    fun add(guildId: Long, roleId: Long): AutoRoleDto
    fun delete(guildId: Long, roleId: Long)
}
