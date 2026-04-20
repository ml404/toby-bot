package database.service.impl

import database.dto.CampaignDto
import database.persistence.CampaignPersistence
import database.service.CampaignService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultCampaignService(
    private val campaignPersistence: CampaignPersistence
) : CampaignService {

    override fun createCampaign(campaign: CampaignDto): CampaignDto =
        campaignPersistence.createCampaign(campaign)

    override fun getCampaignById(id: Long): CampaignDto? =
        campaignPersistence.getCampaignById(id)

    override fun getActiveCampaignForGuild(guildId: Long): CampaignDto? =
        campaignPersistence.getActiveCampaignForGuild(guildId)

    override fun listActiveCampaigns(): List<CampaignDto> =
        campaignPersistence.listActiveCampaigns()

    override fun updateCampaign(campaign: CampaignDto): CampaignDto =
        campaignPersistence.updateCampaign(campaign)

    override fun deactivateCampaignForGuild(guildId: Long) =
        campaignPersistence.deactivateCampaignForGuild(guildId)
}
