package database.persistence.activity.impl

import database.dto.activity.InstallEventDto
import database.dto.activity.InstallEventType
import database.persistence.activity.InstallEventPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
@Transactional
class DefaultInstallEventPersistence : InstallEventPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun record(guildId: Long, type: InstallEventType, occurredAt: Instant) {
        entityManager.persist(
            InstallEventDto(guildId = guildId, eventType = type.name, occurredAt = occurredAt)
        )
        entityManager.flush()
    }

    override fun countByType(type: InstallEventType): Long {
        val q: TypedQuery<Long> =
            entityManager.createNamedQuery("InstallEventDto.countByType", Long::class.javaObjectType)
        q.setParameter("eventType", type.name)
        return q.singleResult ?: 0L
    }

    override fun countByTypeSince(type: InstallEventType, since: Instant): Long {
        val q: TypedQuery<Long> =
            entityManager.createNamedQuery("InstallEventDto.countByTypeSince", Long::class.javaObjectType)
        q.setParameter("eventType", type.name)
        q.setParameter("since", since)
        return q.singleResult ?: 0L
    }

    override fun findSince(since: Instant): List<InstallEventDto> {
        val q: TypedQuery<InstallEventDto> =
            entityManager.createNamedQuery("InstallEventDto.findSince", InstallEventDto::class.java)
        q.setParameter("since", since)
        return q.resultList
    }
}
