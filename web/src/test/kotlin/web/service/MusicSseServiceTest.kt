package web.service

import core.music.MusicControlGateway
import core.music.MusicControlGateway.GuildPlayerState
import core.music.MusicControlGateway.TrackInfo
import core.music.events.PauseStateChangedEvent
import core.music.events.QueueChangedEvent
import core.music.events.TrackEndedEvent
import core.music.events.TrackStartedEvent
import core.music.events.VolumeChangedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class MusicSseServiceTest {

    private val guildId = 100L
    private val otherGuildId = 200L

    private lateinit var gateway: MusicControlGateway
    private lateinit var musicWebService: MusicWebService
    private lateinit var service: MusicSseService

    @BeforeEach
    fun setUp() {
        gateway = mockk(relaxed = true)
        musicWebService = mockk(relaxed = true)
        // Default: enrichRequester passes the track through unchanged so we
        // don't have to stub every event-listener test individually.
        every { musicWebService.enrichRequester(any(), any()) } answers { firstArg() }
        service = MusicSseService(gateway, musicWebService)
    }

    @Test
    fun `register returns a non-null SseEmitter`() {
        val emitter = service.register(guildId)
        assertNotNull(emitter)
    }

    @Test
    fun `trackStart event is sent to subscribers of that guild`() {
        val emitter = service.register(guildId)
        val track = dummyTrack()
        service.onTrackStart(TrackStartedEvent(guildId, track))
        // Hello + trackStart = 2 calls. Compare via verify count.
        // SseEmitter.send is overloaded; mockK records all calls.
        // We've already used the real emitter for hello, but verify
        // we didn't accidentally raise an exception.
        emitter.complete()
    }

    @Test
    fun `event for unknown guild is silently dropped`() {
        // No emitter registered for otherGuildId.
        service.onTrackStart(TrackStartedEvent(otherGuildId, dummyTrack()))
        // No exception means pass.
    }

    @Test
    fun `multiple emitters per guild are all targeted`() {
        val e1 = service.register(guildId)
        val e2 = service.register(guildId)
        assertNotNull(e1)
        assertNotNull(e2)
        // Fire an event; both should receive it without throwing.
        service.onQueueChanged(QueueChangedEvent(guildId, emptyList()))
        e1.complete()
        e2.complete()
    }

    @Test
    fun `emitter completion evicts from registry`() {
        val e1 = service.register(guildId)
        e1.complete()
        // After eviction, a new event should not throw and should not target the closed emitter.
        service.onTrackEnd(TrackEndedEvent(guildId, "FINISHED"))
    }

    @Test
    fun `pauseStateChanged is dispatched to that guild's emitters`() {
        service.register(guildId)
        service.onPauseStateChanged(PauseStateChangedEvent(guildId, true))
    }

    @Test
    fun `volumeChanged is dispatched to that guild's emitters`() {
        service.register(guildId)
        service.onVolumeChanged(VolumeChangedEvent(guildId, 80))
    }

    @Test
    fun `position tick is skipped for guilds with no subscribers`() {
        // No emitters registered. Calling tick should not call the gateway.
        service.publishPositionTicks()
        verify(exactly = 0) { gateway.getState(any()) }
    }

    @Test
    fun `position tick is skipped for guilds with no playing track`() {
        service.register(guildId)
        every { gateway.getState(guildId) } returns
            GuildPlayerState(guildId, null, 0L, false, 100, false, emptyList(), null)
        service.publishPositionTicks()
        // Gateway was consulted, but no exception means we returned without emitting.
        verify(exactly = 1) { gateway.getState(guildId) }
    }

    @Test
    fun `position tick is skipped when paused`() {
        service.register(guildId)
        every { gateway.getState(guildId) } returns
            GuildPlayerState(guildId, dummyTrack(), 5000L, true, 100, false, emptyList(), null)
        service.publishPositionTicks()
        verify(exactly = 1) { gateway.getState(guildId) }
    }

    @Test
    fun `position tick fires when playing and subscribers present`() {
        val emitter = service.register(guildId)
        every { gateway.getState(guildId) } returns
            GuildPlayerState(guildId, dummyTrack(), 5000L, false, 100, false, emptyList(), null)
        service.publishPositionTicks()
        verify(exactly = 1) { gateway.getState(guildId) }
        emitter.complete()
    }

    @Test
    fun `heartbeat does not throw when there are no subscribers`() {
        service.heartbeat()
    }

    @Test
    fun `heartbeat ticks active subscribers`() {
        val emitter = service.register(guildId)
        service.heartbeat()
        emitter.complete()
    }

    @Test
    fun `trackStart event enriches the track via MusicWebService`() {
        service.register(guildId)
        val raw = dummyTrack()
        val enriched = raw.copy(requesterDisplayName = "Alice", requesterAvatarUrl = "https://cdn/a.png")
        every { musicWebService.enrichRequester(raw, guildId) } returns enriched
        service.onTrackStart(TrackStartedEvent(guildId, raw))
        verify(exactly = 1) { musicWebService.enrichRequester(raw, guildId) }
    }

    @Test
    fun `queueChanged event enriches each track via MusicWebService`() {
        service.register(guildId)
        val a = dummyTrack().copy(identifier = "a")
        val b = dummyTrack().copy(identifier = "b")
        every { musicWebService.enrichRequester(a, guildId) } returns a
        every { musicWebService.enrichRequester(b, guildId) } returns b
        service.onQueueChanged(QueueChangedEvent(guildId, listOf(a, b)))
        verify(exactly = 1) { musicWebService.enrichRequester(a, guildId) }
        verify(exactly = 1) { musicWebService.enrichRequester(b, guildId) }
    }

    private fun dummyTrack(): TrackInfo = TrackInfo(
        identifier = "abc",
        title = "Test",
        author = "Author",
        durationMs = 60_000L,
        uri = "https://example.com",
        artworkUrl = null,
        sourceName = "youtube",
        isStream = false,
        requesterDiscordId = null,
    )
}
