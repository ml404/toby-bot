package database.persistence.impl

import database.dto.CampaignDto
import database.persistence.CampaignPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultCampaignPersistence : CampaignPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun createCampaign(campaign: CampaignDto): CampaignDto {
        entityManager.persist(campaign)
        entityManager.flush()
        return campaign
    }

    override fun getCampaignById(id: Long): CampaignDto? {
        val q = entityManager.createNamedQuery("CampaignDto.getById", CampaignDto::class.java)
        q.setParameter("id", id)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun getActiveCampaignForGuild(guildId: Long): CampaignDto? {
        val q = entityManager.createNamedQuery("CampaignDto.getByGuildActive", CampaignDto::class.java)
        q.setParameter("guildId", guildId)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun listActiveCampaigns(): List<CampaignDto> =
        entityManager.createNamedQuery("CampaignDto.listActive", CampaignDto::class.java).resultList

    override fun updateCampaign(campaign: CampaignDto): CampaignDto {
        entityManager.merge(campaign)
        entityManager.flush()
        return campaign
    }

    override fun deactivateCampaignForGuild(guildId: Long) {
        val q = entityManager.createNamedQuery("CampaignDto.deactivateByGuild")
        q.setParameter("guildId", guildId)
        q.executeUpdate()
    }
}
