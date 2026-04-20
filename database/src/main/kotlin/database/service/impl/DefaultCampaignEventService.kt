package database.service.impl

import database.dto.CampaignEventDto
import database.persistence.CampaignEventPersistence
import database.service.CampaignEventService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultCampaignEventService(
    private val campaignEventPersistence: CampaignEventPersistence
) : CampaignEventService {

    override fun append(event: CampaignEventDto): CampaignEventDto =
        campaignEventPersistence.append(event)

    override fun getById(id: Long): CampaignEventDto? =
        campaignEventPersistence.getById(id)

    override fun listRecent(campaignId: Long, limit: Int): List<CampaignEventDto> =
        campaignEventPersistence.listRecent(campaignId, limit)

    override fun listSince(campaignId: Long, sinceId: Long, limit: Int): List<CampaignEventDto> =
        campaignEventPersistence.listSince(campaignId, sinceId, limit)
}
