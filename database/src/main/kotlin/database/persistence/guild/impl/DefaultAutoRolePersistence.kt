package database.persistence.guild.impl

import database.dto.guild.AutoRoleDto
import database.persistence.guild.AutoRolePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultAutoRolePersistence : AutoRolePersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listForGuild(guildId: Long): List<AutoRoleDto> {
        val q: TypedQuery<AutoRoleDto> = entityManager
            .createNamedQuery("AutoRoleDto.getGuildAll", AutoRoleDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun exists(guildId: Long, roleId: Long): Boolean {
        val q: TypedQuery<AutoRoleDto> = entityManager
            .createNamedQuery("AutoRoleDto.getByGuildAndRole", AutoRoleDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("roleId", roleId)
        return q.resultList.isNotEmpty()
    }

    override fun add(guildId: Long, roleId: Long): AutoRoleDto {
        val existing = findOne(guildId, roleId)
        if (existing != null) return existing
        val dto = AutoRoleDto(guildId = guildId, roleId = roleId)
        entityManager.persist(dto)
        entityManager.flush()
        return dto
    }

    override fun delete(guildId: Long, roleId: Long) {
        val existing = findOne(guildId, roleId) ?: return
        entityManager.remove(existing)
        entityManager.flush()
    }

    private fun findOne(guildId: Long, roleId: Long): AutoRoleDto? {
        val q: TypedQuery<AutoRoleDto> = entityManager
            .createNamedQuery("AutoRoleDto.getByGuildAndRole", AutoRoleDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("roleId", roleId)
        return q.resultList.firstOrNull()
    }
}
