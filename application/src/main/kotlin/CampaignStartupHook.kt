import bot.toby.helpers.DnDHelper
import bot.toby.helpers.InitiativeStateSnapshot
import com.fasterxml.jackson.databind.ObjectMapper
import common.logging.DiscordLogger
import database.service.CampaignService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Restores any persisted initiative tracker state into DnDHelper on startup.
 *
 * Read pair of [CampaignShutdownHook]: for every active campaign with a non-blank
 * state column, deserialize the [InitiativeStateSnapshot] and seed DnDHelper so
 * the /init next, previous, and clear buttons work after a restart.
 */
@Component
class CampaignStartupHook(
    private val campaignService: CampaignService,
    private val dndHelper: DnDHelper,
    private val objectMapper: ObjectMapper
) {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun restoreCampaignState() {
        val campaigns = campaignService.listActiveCampaigns().filter { !it.state.isNullOrBlank() }
        if (campaigns.isEmpty()) {
            logger.info("CampaignStartupHook: no persisted initiative state to restore")
            return
        }

        logger.info("CampaignStartupHook: restoring initiative state for ${campaigns.size} campaign(s)")
        campaigns.forEach { campaign ->
            runCatching {
                val snapshot = objectMapper.readValue(campaign.state, InitiativeStateSnapshot::class.java)
                dndHelper.restore(campaign.guildId, snapshot)
            }.onFailure {
                logger.warn("Failed to restore state for guild ${campaign.guildId}: ${it.message}")
            }
        }
    }
}
