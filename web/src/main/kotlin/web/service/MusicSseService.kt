package web.service

import core.music.MusicControlGateway
import core.music.events.LoopStateChangedEvent
import core.music.events.PauseStateChangedEvent
import core.music.events.QueueChangedEvent
import core.music.events.TrackEndedEvent
import core.music.events.TrackStartedEvent
import core.music.events.VolumeChangedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.service.sse.KeyedSseRegistry

@Service
class MusicSseService(
    private val gateway: MusicControlGateway,
    private val musicWebService: MusicWebService,
    private val registry: KeyedSseRegistry<Long> = KeyedSseRegistry(),
) {

    fun register(guildId: Long): SseEmitter =
        registry.register(guildId, mapOf("guildId" to guildId))

    @EventListener
    fun onTrackStart(event: TrackStartedEvent) {
        val enriched = event.copy(track = musicWebService.enrichRequester(event.track, event.guildId))
        registry.fanOut(event.guildId, "trackStart", enriched)
    }

    @EventListener
    fun onTrackEnd(event: TrackEndedEvent) =
        registry.fanOut(event.guildId, "trackEnd", event)

    @EventListener
    fun onQueueChanged(event: QueueChangedEvent) {
        val enriched = event.copy(
            queue = event.queue.map { musicWebService.enrichRequester(it, event.guildId) },
        )
        registry.fanOut(event.guildId, "queueChanged", enriched)
    }

    @EventListener
    fun onPauseStateChanged(event: PauseStateChangedEvent) =
        registry.fanOut(event.guildId, "pauseStateChanged", event)

    @EventListener
    fun onVolumeChanged(event: VolumeChangedEvent) =
        registry.fanOut(event.guildId, "volumeChanged", event)

    @EventListener
    fun onLoopStateChanged(event: LoopStateChangedEvent) =
        registry.fanOut(event.guildId, "loopStateChanged", event)

    /**
     * Per-second position tick. We don't emit for guilds with no active
     * subscribers or no playing track — saves both CPU and bandwidth.
     */
    @Scheduled(fixedRate = 1000)
    fun publishPositionTicks() {
        registry.forEachActiveKey { guildId ->
            if (registry.bucketIsEmpty(guildId)) return@forEachActiveKey
            val state = gateway.getState(guildId) ?: return@forEachActiveKey
            val track = state.nowPlaying ?: return@forEachActiveKey
            if (state.paused) return@forEachActiveKey
            registry.fanOut(
                key = guildId,
                eventName = "positionTick",
                payload = mapOf(
                    "guildId" to guildId,
                    "positionMs" to state.positionMs,
                    "durationMs" to track.durationMs,
                ),
            )
        }
    }

    /**
     * Proxy heartbeat so idle SSE connections don't get torn down by
     * intermediaries (e.g. Heroku's 55s idle timeout).
     */
    @Scheduled(fixedRate = 15_000)
    fun heartbeat() {
        registry.heartbeat()
    }

    /**
     * Drop every emitter for [guildId] and remove the bucket. Called when
     * the bot leaves the guild — keeping stale browser channels around
     * holds onto buffers and the `gateway.getState` lookups become moot
     * once the guild is gone.
     */
    fun evictGuild(guildId: Long) {
        registry.evict(guildId)
    }
}
