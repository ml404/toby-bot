package database.persistence.impl

import database.dto.TeamSplitSessionDto
import database.persistence.TeamSplitSessionPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
@Transactional
class DefaultTeamSplitSessionPersistence internal constructor() : TeamSplitSessionPersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(dto: TeamSplitSessionDto): TeamSplitSessionDto {
        entityManager.persist(dto)
        entityManager.flush()
        return dto
    }

    override fun getById(id: UUID): TeamSplitSessionDto? {
        val q: TypedQuery<TeamSplitSessionDto> =
            entityManager.createNamedQuery("TeamSplitSessionDto.getById", TeamSplitSessionDto::class.java)
        q.setParameter("id", id)
        return try {
            q.singleResult
        } catch (_: NoResultException) {
            null
        }
    }

    override fun update(dto: TeamSplitSessionDto): TeamSplitSessionDto {
        val merged = entityManager.merge(dto)
        entityManager.flush()
        return merged
    }

    override fun deleteById(id: UUID) {
        val q = entityManager.createNamedQuery("TeamSplitSessionDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }

    override fun deleteOlderThan(cutoff: Instant): Int {
        val q = entityManager.createNamedQuery("TeamSplitSessionDto.deleteOlderThan")
        q.setParameter("cutoff", cutoff)
        return q.executeUpdate()
    }

    override fun recentForGuild(guildId: Long, limit: Int): List<TeamSplitSessionDto> {
        val q: TypedQuery<TeamSplitSessionDto> =
            entityManager.createNamedQuery("TeamSplitSessionDto.recentForGuild", TeamSplitSessionDto::class.java)
        q.setParameter("guildId", guildId)
        q.maxResults = limit.coerceAtLeast(1)
        return q.resultList
    }
}
