package database.service.impl

import database.dto.AutoRoleDto
import database.persistence.AutoRolePersistence
import database.service.AutoRoleService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class DefaultAutoRoleService @Autowired constructor(
    private val persistence: AutoRolePersistence
) : AutoRoleService {
    override fun listForGuild(guildId: Long): List<AutoRoleDto> =
        persistence.listForGuild(guildId)

    override fun add(guildId: Long, roleId: Long): AutoRoleDto =
        persistence.add(guildId, roleId)

    override fun delete(guildId: Long, roleId: Long) =
        persistence.delete(guildId, roleId)
}
