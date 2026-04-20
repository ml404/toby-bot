import bot.toby.helpers.DnDHelper
import com.fasterxml.jackson.databind.ObjectMapper
import common.logging.DiscordLogger
import database.service.CampaignService
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * Persists in-memory campaign state to the database on application shutdown (#218).
 *
 * For each guild whose DnDHelper currently holds an active initiative tracker, we
 * look up the guild's active campaign and write the snapshot as JSON into
 * [database.dto.CampaignDto.state]. Startup restoration happens in
 * [CampaignStartupHook].
 */
@Component
class CampaignShutdownHook(
    private val campaignService: CampaignService,
    private val dndHelper: DnDHelper,
    private val objectMapper: ObjectMapper
) : DisposableBean {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun destroy() {
        val snapshots = dndHelper.activeSnapshots()
        if (snapshots.isEmpty()) {
            logger.info("CampaignShutdownHook: no in-memory initiative state to persist")
            return
        }

        logger.info("CampaignShutdownHook: persisting initiative state for ${snapshots.size} guild(s)")
        snapshots.forEach { (guildId, snapshot) ->
            val campaign = campaignService.getActiveCampaignForGuild(guildId)
            if (campaign == null) {
                logger.warn("Guild $guildId has initiative state but no active campaign; skipping")
                return@forEach
            }
            runCatching {
                campaign.state = objectMapper.writeValueAsString(snapshot)
                campaignService.updateCampaign(campaign)
            }.onFailure { logger.error("Failed to persist state for guild $guildId: ${it.message}") }
        }
    }
}
