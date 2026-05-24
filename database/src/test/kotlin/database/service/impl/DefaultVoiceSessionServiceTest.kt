package database.service.impl

import common.events.VoiceSessionLoggedEvent
import database.dto.VoiceSessionDto
import database.persistence.VoiceSessionPersistence
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import database.service.activity.impl.DefaultVoiceSessionService

class DefaultVoiceSessionServiceTest {

    private val discordId = 100L
    private val guildId = 42L

    private lateinit var persistence: VoiceSessionPersistence
    private lateinit var eventPublisher: RecordingEventPublisher
    private lateinit var service: DefaultVoiceSessionService

    @BeforeEach
    fun setup() {
        persistence = mockk(relaxed = true)
        eventPublisher = RecordingEventPublisher()
        service = DefaultVoiceSessionService(persistence, eventPublisher)
    }

    @Test
    fun `closeSession publishes a VoiceSessionLoggedEvent with the counted seconds`() {
        val raw = VoiceSessionDto(
            discordId = discordId, guildId = guildId,
            channelId = 1L, joinedAt = Instant.now()
        )
        val persisted = VoiceSessionDto(
            id = 1L, discordId = discordId, guildId = guildId,
            channelId = 1L, joinedAt = Instant.now(),
            countedSeconds = 600L
        )
        every { persistence.closeSession(raw) } returns persisted

        service.closeSession(raw)

        assertEquals(1, eventPublisher.voiceEvents.size)
        val event = eventPublisher.voiceEvents.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
        assertEquals(600L, event.countedSeconds)
    }

    @Test
    fun `closeSession does not publish when countedSeconds is zero or null`() {
        val raw = VoiceSessionDto(discordId = discordId, guildId = guildId, channelId = 1L, joinedAt = Instant.now())
        // Zero counted seconds — user joined but had no company.
        val zeroed = VoiceSessionDto(
            id = 1L, discordId = discordId, guildId = guildId,
            channelId = 1L, joinedAt = Instant.now(),
            countedSeconds = 0L
        )
        every { persistence.closeSession(raw) } returns zeroed

        service.closeSession(raw)

        assertTrue(eventPublisher.voiceEvents.isEmpty())

        // Null counted seconds — defensive case.
        val nulled = VoiceSessionDto(
            id = 2L, discordId = discordId, guildId = guildId,
            channelId = 1L, joinedAt = Instant.now(),
            countedSeconds = null
        )
        every { persistence.closeSession(any()) } returns nulled
        service.closeSession(raw)
        assertTrue(eventPublisher.voiceEvents.isEmpty())
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val voiceEvents: MutableList<VoiceSessionLoggedEvent> = mutableListOf()
        override fun publishEvent(event: ApplicationEvent) {}
        override fun publishEvent(event: Any) {
            if (event is VoiceSessionLoggedEvent) voiceEvents.add(event)
        }
    }
}
