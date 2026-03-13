package database.service.impl

import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.persistence.CampaignPlayerPersistence
import database.service.CampaignPlayerService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultCampaignPlayerService(
    private val campaignPlayerPersistence: CampaignPlayerPersistence
) : CampaignPlayerService {

    override fun addPlayer(player: CampaignPlayerDto): CampaignPlayerDto =
        campaignPlayerPersistence.addPlayer(player)

    override fun getPlayer(id: CampaignPlayerId): CampaignPlayerDto? =
        campaignPlayerPersistence.getPlayer(id)

    override fun getPlayersForCampaign(campaignId: Long): List<CampaignPlayerDto> =
        campaignPlayerPersistence.getPlayersForCampaign(campaignId)

    override fun updatePlayer(player: CampaignPlayerDto): CampaignPlayerDto =
        campaignPlayerPersistence.updatePlayer(player)

    override fun removePlayer(id: CampaignPlayerId) =
        campaignPlayerPersistence.removePlayer(id)
}
