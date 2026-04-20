package database.persistence.impl

import database.dto.CampaignEventDto
import database.persistence.CampaignEventPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultCampaignEventPersistence : CampaignEventPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun append(event: CampaignEventDto): CampaignEventDto {
        entityManager.persist(event)
        entityManager.flush()
        return event
    }

    override fun getById(id: Long): CampaignEventDto? {
        val q = entityManager.createNamedQuery("CampaignEventDto.getById", CampaignEventDto::class.java)
        q.setParameter("id", id)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun listRecent(campaignId: Long, limit: Int): List<CampaignEventDto> {
        val q = entityManager.createNamedQuery("CampaignEventDto.getByCampaignDesc", CampaignEventDto::class.java)
        q.setParameter("campaignId", campaignId)
        q.maxResults = limit
        return q.resultList.reversed()
    }

    override fun listSince(campaignId: Long, sinceId: Long, limit: Int): List<CampaignEventDto> {
        val q = entityManager.createNamedQuery("CampaignEventDto.getSinceId", CampaignEventDto::class.java)
        q.setParameter("campaignId", campaignId)
        q.setParameter("sinceId", sinceId)
        q.maxResults = limit
        return q.resultList
    }
}
