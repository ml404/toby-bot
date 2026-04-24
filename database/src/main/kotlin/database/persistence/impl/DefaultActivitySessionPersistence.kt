package database.persistence.impl

import database.dto.ActivitySessionDto
import database.persistence.ActivitySessionPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
@Transactional
class DefaultActivitySessionPersistence : ActivitySessionPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun openSession(session: ActivitySessionDto): ActivitySessionDto {
        entityManager.persist(session)
        entityManager.flush()
        return session
    }

    override fun closeSession(session: ActivitySessionDto): ActivitySessionDto {
        entityManager.merge(session)
        entityManager.flush()
        return session
    }

    override fun findOpen(discordId: Long, guildId: Long): ActivitySessionDto? {
        val q: TypedQuery<ActivitySessionDto> =
            entityManager.createNamedQuery("ActivitySessionDto.findOpen", ActivitySessionDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        return q.resultList.firstOrNull()
    }

    override fun findAllOpen(): List<ActivitySessionDto> {
        val q: TypedQuery<ActivitySessionDto> =
            entityManager.createNamedQuery("ActivitySessionDto.findAllOpen", ActivitySessionDto::class.java)
        return q.resultList
    }

    override fun findClosedBefore(cutoff: Instant): List<ActivitySessionDto> {
        val q: TypedQuery<ActivitySessionDto> =
            entityManager.createNamedQuery("ActivitySessionDto.findClosedBefore", ActivitySessionDto::class.java)
        q.setParameter("cutoff", cutoff)
        return q.resultList
    }

    override fun deleteClosedBefore(cutoff: Instant): Int {
        val q = entityManager.createNamedQuery("ActivitySessionDto.deleteClosedBefore")
        q.setParameter("cutoff", cutoff)
        return q.executeUpdate()
    }
}
