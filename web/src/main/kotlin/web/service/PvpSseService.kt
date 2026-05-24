package web.service

import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.service.sse.KeyedSseRegistry

/**
 * Per-user SSE channel for the `/pvp` page. One emitter per
 * `(guildId, viewerDiscordId)` carries every PvP event relevant to
 * that user in that guild — new offers, accepts, declines, picks,
 * resolutions — across all four games.
 *
 * Storage and lifecycle are delegated to [KeyedSseRegistry] (see its
 * KDoc for the memory-profile rationale). This service is purely about
 * fan-out routing — the registry handles dead-emitter eviction and
 * bucket lifetime.
 *
 * One stream per `(guildId, discordId)` instead of per-`(sessionId)`:
 * a user can be in multiple matches at once across games, and the
 * inbox feed isn't tied to any one session id. Multiplexing through a
 * single channel keyed on the user keeps the client to one
 * EventSource regardless of how many sessions they're juggling.
 */
@Service
class PvpSseService(
    private val registry: KeyedSseRegistry<Key> = KeyedSseRegistry(),
) {

    /**
     * Composite key for the registry. A user has one logical stream per
     * guild — opening a second tab on `/pvp/{guildId}` adds another
     * emitter under the same key, and the fan-out broadcast hits both.
     */
    data class Key(val guildId: Long, val discordId: Long)

    fun register(guildId: Long, discordId: Long): SseEmitter =
        registry.register(
            key = Key(guildId, discordId),
            helloPayload = mapOf("guildId" to guildId, "discordId" to discordId.toString()),
        )

    /** Fan out one event to one user (e.g. "you have a new RPS offer"). */
    fun fanOutToUser(guildId: Long, discordId: Long, eventName: String, payload: Any) {
        registry.fanOut(Key(guildId, discordId), eventName, payload)
    }

    /** Fan out the same event to both participants of a session. */
    fun fanOutToBoth(
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        eventName: String,
        payload: Any,
    ) {
        fanOutToUser(guildId, initiatorDiscordId, eventName, payload)
        fanOutToUser(guildId, opponentDiscordId, eventName, payload)
    }
}
