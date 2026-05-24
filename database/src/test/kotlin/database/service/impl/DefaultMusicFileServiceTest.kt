package database.service.impl

import common.events.IntroSetEvent
import database.dto.music.MusicDto
import database.dto.user.UserDto
import database.persistence.music.MusicFilePersistence
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import database.service.music.impl.DefaultMusicFileService

class DefaultMusicFileServiceTest {

    private val discordId = 100L
    private val guildId = 42L

    private lateinit var persistence: MusicFilePersistence
    private lateinit var eventPublisher: RecordingEventPublisher
    private lateinit var service: DefaultMusicFileService

    @BeforeEach
    fun setup() {
        persistence = mockk(relaxed = true)
        eventPublisher = RecordingEventPublisher()
        service = DefaultMusicFileService(eventPublisher).apply {
            // The @Autowired lateinit field is set by Spring in production —
            // poke it via reflection-light alternative: re-declare the
            // persistence by directly assigning. Use the Kotlin
            // reflection-free approach: the field is internal package, so
            // we use Java reflection.
            javaClass.getDeclaredField("musicFileService").apply { isAccessible = true }.set(this, persistence)
        }
    }

    @Test
    fun `createNewMusicFile publishes an IntroSetEvent on success`() {
        val user = UserDto(discordId, guildId)
        val dto = MusicDto(user, 1, "song.mp3", 90, byteArrayOf(1, 2, 3))
        every { persistence.createNewMusicFile(dto) } returns dto

        service.createNewMusicFile(dto)

        assertEquals(1, eventPublisher.introEvents.size)
        val event = eventPublisher.introEvents.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
    }

    @Test
    fun `createNewMusicFile does not publish when persistence returns null`() {
        val user = UserDto(discordId, guildId)
        val dto = MusicDto(user, 1, "song.mp3", 90, byteArrayOf(1))
        every { persistence.createNewMusicFile(dto) } returns null

        service.createNewMusicFile(dto)

        assertTrue(eventPublisher.introEvents.isEmpty())
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val introEvents: MutableList<IntroSetEvent> = mutableListOf()
        override fun publishEvent(event: ApplicationEvent) {}
        override fun publishEvent(event: Any) {
            if (event is IntroSetEvent) introEvents.add(event)
        }
    }
}
