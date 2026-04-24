package database.persistence.impl

import database.dto.GuildTitleRoleDto
import database.dto.GuildTitleRoleId
import database.persistence.GuildTitleRolePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultGuildTitleRolePersistence : GuildTitleRolePersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(guildId: Long, titleId: Long): GuildTitleRoleDto? {
        val q: TypedQuery<GuildTitleRoleDto> =
            entityManager.createNamedQuery("GuildTitleRoleDto.get", GuildTitleRoleDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("titleId", titleId)
        return q.resultList.firstOrNull()
    }

    override fun listByGuild(guildId: Long): List<GuildTitleRoleDto> {
        val q: TypedQuery<GuildTitleRoleDto> =
            entityManager.createNamedQuery("GuildTitleRoleDto.getByGuild", GuildTitleRoleDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun save(dto: GuildTitleRoleDto): GuildTitleRoleDto {
        val existing = get(dto.guildId, dto.titleId)
        return if (existing == null) {
            entityManager.persist(dto)
            entityManager.flush()
            dto
        } else {
            existing.discordRoleId = dto.discordRoleId
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }

    override fun delete(guildId: Long, titleId: Long) {
        val existing = entityManager.find(GuildTitleRoleDto::class.java, GuildTitleRoleId(guildId, titleId))
        if (existing != null) {
            entityManager.remove(existing)
            entityManager.flush()
        }
    }
}
