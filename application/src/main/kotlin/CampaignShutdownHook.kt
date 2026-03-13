import common.logging.DiscordLogger
import database.service.CampaignService
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * Persists in-memory campaign state to the database on application shutdown (#218).
 *
 * In Phase 1 there is no transient in-memory state beyond what is already in the DB,
 * so this hook is a no-op. Phase 3 will populate `CampaignDto.state` with a JSON
 * snapshot of initiative order, roll history, and monster lists so sessions can resume
 * after a restart.
 */
@Component
class CampaignShutdownHook(
    private val campaignService: CampaignService
) : DisposableBean {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun destroy() {
        logger.info("CampaignShutdownHook: persisting campaign state before shutdown")
        // Phase 3: iterate active campaigns, serialise in-memory state to CampaignDto.state
    }
}
