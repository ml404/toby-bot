import common.events.CampaignEventOccurred
import common.logging.DiscordLogger
import database.dto.CampaignEventDto
import database.service.CampaignEventService
import database.service.CampaignService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import web.service.CampaignEventBroadcaster
import java.time.LocalDateTime

/**
 * Persists every [CampaignEventOccurred] to `dnd_campaign_event` for the guild's
 * currently active campaign, then fans the persisted row out to any SSE subscribers
 * watching that campaign (via [CampaignEventBroadcaster]).
 *
 * Events for guilds without an active campaign are dropped silently — callers like
 * `/roll` fire unconditionally and shouldn't fail or leak state.
 */
@Component
class CampaignEventListener(
    private val campaignService: CampaignService,
    private val campaignEventService: CampaignEventService,
    private val broadcaster: CampaignEventBroadcaster
) {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun onCampaignEvent(event: CampaignEventOccurred) {
        val campaign = campaignService.getActiveCampaignForGuild(event.guildId) ?: return

        val persisted = runCatching {
            campaignEventService.append(
                CampaignEventDto(
                    campaignId = campaign.id,
                    eventType = event.type.name,
                    actorDiscordId = event.actorDiscordId,
                    actorName = event.actorName,
                    refEventId = event.refEventId,
                    payload = event.payloadJson,
                    createdAt = LocalDateTime.now()
                )
            )
        }.onFailure {
            logger.warn("Failed to persist campaign event ${event.type} for guild ${event.guildId}: ${it.message}")
        }.getOrNull() ?: return

        runCatching { broadcaster.publish(campaign.id, persisted) }
            .onFailure { logger.warn("Failed to broadcast campaign event ${persisted.id}: ${it.message}") }
    }
}
