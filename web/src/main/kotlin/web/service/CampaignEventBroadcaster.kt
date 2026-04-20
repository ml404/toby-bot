package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import database.dto.CampaignEventDto
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out hub for live campaign events. Browsers subscribe via
 * [GET /dnd/campaign/{guildId}/events/stream]; the campaign event listener
 * calls [publish] after persisting each event. One emitter list per campaign;
 * disconnected emitters are pruned on completion, timeout, or send failure.
 */
@Service
class CampaignEventBroadcaster(private val objectMapper: ObjectMapper) {

    private val emitters: ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> = ConcurrentHashMap()

    fun subscribe(campaignId: Long): SseEmitter {
        val emitter = SseEmitter(EMITTER_TIMEOUT_MS)
        val list = emitters.computeIfAbsent(campaignId) { CopyOnWriteArrayList() }
        list.add(emitter)

        val detach: () -> Unit = { list.remove(emitter) }
        emitter.onCompletion(detach)
        emitter.onTimeout(detach)
        emitter.onError { detach() }

        // Initial handshake event so the browser closes the loading spinner.
        runCatching {
            emitter.send(SseEmitter.event().name("connected").data("{}"))
        }.onFailure { detach() }

        return emitter
    }

    fun publish(campaignId: Long, dto: CampaignEventDto) {
        val list = emitters[campaignId] ?: return
        val json = objectMapper.writeValueAsString(toSessionEventView(dto))
        val failed = mutableListOf<SseEmitter>()
        list.forEach { emitter ->
            runCatching {
                emitter.send(SseEmitter.event().name("event").data(json))
            }.onFailure { failed += emitter }
        }
        if (failed.isNotEmpty()) list.removeAll(failed)
    }

    fun subscriberCount(campaignId: Long): Int = emitters[campaignId]?.size ?: 0

    private val payloadTypeRef =
        object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}

    private fun toSessionEventView(dto: CampaignEventDto): SessionEventView {
        val parsed = runCatching { objectMapper.readValue(dto.payload, payloadTypeRef) }
            .getOrDefault(emptyMap())
        return SessionEventView(
            id = dto.id,
            type = dto.eventType,
            actorDiscordId = dto.actorDiscordId,
            actorName = dto.actorName,
            refEventId = dto.refEventId,
            payload = parsed,
            createdAt = dto.createdAt
        )
    }

    companion object {
        /** Browsers reconnect automatically on EventSource timeout, so 5min is fine. */
        val EMITTER_TIMEOUT_MS: Long = Duration.ofMinutes(5).toMillis()
    }
}
