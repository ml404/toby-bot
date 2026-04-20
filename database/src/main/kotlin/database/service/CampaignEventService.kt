package database.service

import database.dto.CampaignEventDto

interface CampaignEventService {
    fun append(event: CampaignEventDto): CampaignEventDto
    fun getById(id: Long): CampaignEventDto?
    fun listRecent(campaignId: Long, limit: Int): List<CampaignEventDto>
    fun listSince(campaignId: Long, sinceId: Long, limit: Int): List<CampaignEventDto>
}
