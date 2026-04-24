package database.service.impl

import database.dto.GuildTitleRoleDto
import database.persistence.GuildTitleRolePersistence
import database.service.GuildTitleRoleService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class DefaultGuildTitleRoleService @Autowired constructor(
    private val persistence: GuildTitleRolePersistence
) : GuildTitleRoleService {
    override fun get(guildId: Long, titleId: Long): GuildTitleRoleDto? = persistence.get(guildId, titleId)
    override fun listByGuild(guildId: Long): List<GuildTitleRoleDto> = persistence.listByGuild(guildId)
    override fun save(dto: GuildTitleRoleDto): GuildTitleRoleDto = persistence.save(dto)
    override fun delete(guildId: Long, titleId: Long) = persistence.delete(guildId, titleId)
}
