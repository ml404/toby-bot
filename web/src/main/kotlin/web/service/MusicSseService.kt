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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Service
class MusicSseService(
    private val gateway: MusicControlGateway,
    private val musicWebService: MusicWebService,
) {
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    fun register(guildId: Long): SseEmitter {
        val emitter = SseEmitter(EMITTER_TIMEOUT_MS)
        val list = emitters.computeIfAbsent(guildId) { CopyOnWriteArrayList() }
        list.add(emitter)
        emitter.onCompletion { list.remove(emitter) }
        emitter.onTimeout { list.remove(emitter); emitter.complete() }
        emitter.onError { list.remove(emitter) }
        // Send an immediate hello so the client knows the channel is live.
        runCatching {
            emitter.send(SseEmitter.event().name("hello").data(mapOf("guildId" to guildId)))
        }.onFailure { list.remove(emitter) }
        return emitter
    }

    @EventListener
    fun onTrackStart(event: TrackStartedEvent) {
        val enriched = event.copy(track = musicWebService.enrichRequester(event.track, event.guildId))
        fanOut(event.guildId, "trackStart", enriched)
    }

    @EventListener
    fun onTrackEnd(event: TrackEndedEvent) =
        fanOut(event.guildId, "trackEnd", event)

    @EventListener
    fun onQueueChanged(event: QueueChangedEvent) {
        val enriched = event.copy(
            queue = event.queue.map { musicWebService.enrichRequester(it, event.guildId) },
        )
        fanOut(event.guildId, "queueChanged", enriched)
    }

    @EventListener
    fun onPauseStateChanged(event: PauseStateChangedEvent) =
        fanOut(event.guildId, "pauseStateChanged", event)

    @EventListener
    fun onVolumeChanged(event: VolumeChangedEvent) =
        fanOut(event.guildId, "volumeChanged", event)

    @EventListener
    fun onLoopStateChanged(event: LoopStateChangedEvent) =
        fanOut(event.guildId, "loopStateChanged", event)

    /**
     * Per-second position tick. We don't emit for guilds with no active
     * subscribers or no playing track — saves both CPU and bandwidth.
     */
    @Scheduled(fixedRate = 1000)
    fun publishPositionTicks() {
        for ((guildId, list) in emitters) {
            if (list.isEmpty()) continue
            val state = gateway.getState(guildId) ?: continue
            val track = state.nowPlaying ?: continue
            if (state.paused) continue
            fanOut(
                guildId,
                "positionTick",
                mapOf(
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
        for (list in emitters.values) {
            broadcast(list) { send(SseEmitter.event().comment("hb")) }
        }
    }

    /**
     * Drop every emitter for [guildId] and remove the bucket. Called when
     * the bot leaves the guild — keeping stale browser channels around
     * holds onto buffers and the `gateway.getState` lookups become moot
     * once the guild is gone.
     */
    fun evictGuild(guildId: Long) {
        val list = emitters.remove(guildId) ?: return
        for (emitter in list) {
            runCatching { emitter.complete() }
        }
    }

    private fun fanOut(guildId: Long, name: String, payload: Any) {
        val list = emitters[guildId] ?: return
        broadcast(list) { send(SseEmitter.event().name(name).data(payload)) }
    }

    /**
     * Iterate [list] and call [action] on each emitter; emitters that throw
     * IOException / IllegalStateException (typical "client gone" signals) are
     * evicted from the list. Centralises the dead-emitter eviction pattern.
     */
    private inline fun broadcast(list: CopyOnWriteArrayList<SseEmitter>, action: SseEmitter.() -> Unit) {
        if (list.isEmpty()) return
        val dead = mutableListOf<SseEmitter>()
        for (emitter in list) {
            try {
                emitter.action()
            } catch (_: IOException) {
                dead.add(emitter)
            } catch (_: IllegalStateException) {
                dead.add(emitter)
            }
        }
        if (dead.isNotEmpty()) list.removeAll(dead)
    }

    companion object {
        // 1 hour — long enough that the browser will close the channel before
        // we hit it on a normal session; reconnect logic in music-player.js handles
        // any expiry.
        private const val EMITTER_TIMEOUT_MS = 60L * 60L * 1000L
    }
}
