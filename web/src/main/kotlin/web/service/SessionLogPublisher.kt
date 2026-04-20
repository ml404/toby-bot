package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import common.events.CampaignEventOccurred
import common.events.CampaignEventType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Tiny convenience around Spring's [ApplicationEventPublisher] for producing
 * [CampaignEventOccurred] events from both Discord commands and web actions.
 * Lives in `web` because it's the only module with an already-autoconfigured
 * Jackson [ObjectMapper] bean; callers in `discord-bot` reach it through the
 * existing `discord-bot → web` project dependency.
 */
@Service
class SessionLogPublisher(
    private val publisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper
) {

    fun publish(
        guildId: Long,
        type: CampaignEventType,
        actorDiscordId: Long? = null,
        actorName: String? = null,
        payload: Map<String, Any?> = emptyMap(),
        refEventId: Long? = null
    ) {
        publisher.publishEvent(
            CampaignEventOccurred(
                guildId = guildId,
                type = type,
                actorDiscordId = actorDiscordId,
                actorName = actorName,
                payloadJson = objectMapper.writeValueAsString(payload),
                refEventId = refEventId
            )
        )
    }
}
