import common.events.CampaignEventOccurred
import common.logging.DiscordLogger
import database.dto.CampaignEventDto
import database.service.CampaignEventService
import database.service.CampaignService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Persists every [CampaignEventOccurred] to `dnd_campaign_event` for the guild's
 * currently active campaign. Events for guilds without an active campaign are
 * dropped silently — callers like `/roll` fire unconditionally and shouldn't
 * fail or leak state.
 *
 * PR B will add a second listener that broadcasts the persisted event to SSE
 * subscribers; this listener stays the write path.
 */
@Component
class CampaignEventListener(
    private val campaignService: CampaignService,
    private val campaignEventService: CampaignEventService
) {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun onCampaignEvent(event: CampaignEventOccurred) {
        val campaign = campaignService.getActiveCampaignForGuild(event.guildId) ?: return
        runCatching {
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
        }
    }
}
