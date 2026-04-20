package database.persistence

import database.dto.CampaignEventDto

interface CampaignEventPersistence {
    fun append(event: CampaignEventDto): CampaignEventDto
    fun getById(id: Long): CampaignEventDto?
    fun listRecent(campaignId: Long, limit: Int): List<CampaignEventDto>
    fun listSince(campaignId: Long, sinceId: Long, limit: Int): List<CampaignEventDto>
}
