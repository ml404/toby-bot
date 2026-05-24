package database.persistence.guild.impl

import database.dto.guild.TeamPresetDto
import database.persistence.guild.TeamPresetPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultTeamPresetPersistence internal constructor() : TeamPresetPersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listByGuild(guildId: Long): List<TeamPresetDto> {
        val q: TypedQuery<TeamPresetDto> =
            entityManager.createNamedQuery("TeamPresetDto.getByGuild", TeamPresetDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun getByGuildAndName(guildId: Long, name: String): TeamPresetDto? {
        val q: TypedQuery<TeamPresetDto> =
            entityManager.createNamedQuery("TeamPresetDto.getByGuildAndName", TeamPresetDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("name", name)
        return try {
            q.singleResult
        } catch (_: NoResultException) {
            null
        }
    }

    override fun getById(id: Long): TeamPresetDto? {
        val q: TypedQuery<TeamPresetDto> =
            entityManager.createNamedQuery("TeamPresetDto.getById", TeamPresetDto::class.java)
        q.setParameter("id", id)
        return try {
            q.singleResult
        } catch (_: NoResultException) {
            null
        }
    }

    override fun save(dto: TeamPresetDto): TeamPresetDto {
        entityManager.persist(dto)
        entityManager.flush()
        return dto
    }

    override fun update(dto: TeamPresetDto): TeamPresetDto {
        val merged = entityManager.merge(dto)
        entityManager.flush()
        return merged
    }

    override fun deleteById(id: Long) {
        val q = entityManager.createNamedQuery("TeamPresetDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }

    override fun deleteAllByGuild(guildId: Long) {
        val q = entityManager.createNamedQuery("TeamPresetDto.deleteAllByGuild")
        q.setParameter("guildId", guildId)
        q.executeUpdate()
    }
}
