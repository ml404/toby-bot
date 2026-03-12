package database.service

import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId

interface CampaignPlayerService {
    fun addPlayer(player: CampaignPlayerDto): CampaignPlayerDto
    fun getPlayer(id: CampaignPlayerId): CampaignPlayerDto?
    fun getPlayersForCampaign(campaignId: Long): List<CampaignPlayerDto>
    fun updatePlayer(player: CampaignPlayerDto): CampaignPlayerDto
    fun removePlayer(id: CampaignPlayerId)
}
