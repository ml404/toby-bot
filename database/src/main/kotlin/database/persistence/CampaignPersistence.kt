package database.persistence

import database.dto.CampaignDto

interface CampaignPersistence {
    fun createCampaign(campaign: CampaignDto): CampaignDto
    fun getCampaignById(id: Long): CampaignDto?
    fun getActiveCampaignForGuild(guildId: Long): CampaignDto?
    fun updateCampaign(campaign: CampaignDto): CampaignDto
    fun deactivateCampaignForGuild(guildId: Long)
}
