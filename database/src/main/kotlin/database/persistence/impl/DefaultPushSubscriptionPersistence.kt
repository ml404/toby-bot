package database.persistence.impl

import database.dto.PushSubscriptionDto
import database.persistence.PushSubscriptionPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultPushSubscriptionPersistence : PushSubscriptionPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(endpoint: String): PushSubscriptionDto? {
        val q: TypedQuery<PushSubscriptionDto> =
            entityManager.createNamedQuery("PushSubscriptionDto.get", PushSubscriptionDto::class.java)
        q.setParameter("endpoint", endpoint)
        return q.resultList.firstOrNull()
    }

    override fun listForUser(discordId: Long): List<PushSubscriptionDto> {
        val q: TypedQuery<PushSubscriptionDto> =
            entityManager.createNamedQuery("PushSubscriptionDto.listForUser", PushSubscriptionDto::class.java)
        q.setParameter("discordId", discordId)
        return q.resultList
    }

    override fun upsert(row: PushSubscriptionDto): PushSubscriptionDto {
        val existing = get(row.endpoint)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.discordId = row.discordId
            existing.p256dh = row.p256dh
            existing.auth = row.auth
            existing.userAgent = row.userAgent
            existing.lastUsedAt = row.lastUsedAt
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }

    override fun delete(endpoint: String): Int {
        val q = entityManager.createNamedQuery("PushSubscriptionDto.deleteByEndpoint")
        q.setParameter("endpoint", endpoint)
        return q.executeUpdate()
    }
}
