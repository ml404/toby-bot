package database.persistence.impl

import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.persistence.CampaignPlayerPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultCampaignPlayerPersistence : CampaignPlayerPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun addPlayer(player: CampaignPlayerDto): CampaignPlayerDto {
        entityManager.persist(player)
        entityManager.flush()
        return player
    }

    override fun getPlayer(id: CampaignPlayerId): CampaignPlayerDto? =
        entityManager.find(CampaignPlayerDto::class.java, id)

    override fun getPlayersForCampaign(campaignId: Long): List<CampaignPlayerDto> {
        val q = entityManager.createNamedQuery("CampaignPlayerDto.getByCampaign", CampaignPlayerDto::class.java)
        q.setParameter("campaignId", campaignId)
        return q.resultList
    }

    override fun updatePlayer(player: CampaignPlayerDto): CampaignPlayerDto {
        entityManager.merge(player)
        entityManager.flush()
        return player
    }

    override fun removePlayer(id: CampaignPlayerId) {
        val q = entityManager.createNamedQuery("CampaignPlayerDto.deleteById")
        q.setParameter("campaignId", id.campaignId)
        q.setParameter("playerDiscordId", id.playerDiscordId)
        q.executeUpdate()
    }
}
