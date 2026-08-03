package bot.toby.intro

import bot.toby.notify.NotificationRouter
import common.intro.IntroHealth
import common.notification.NotificationChannelKind
import database.dto.music.MusicDto
import database.dto.user.UserDto
import database.service.music.MusicFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The behaviour under test is the one that didn't exist at all: intros play
 * from the voice-join path, which has no interaction to reply to, so a load
 * failure wrote to a null hook and vanished. These assert that the outcome now
 * lands on the row, and that the owner hears about it exactly once.
 */
class IntroHealthServiceTest {

    private val introId = "1_2_1"

    private lateinit var musicFileService: MusicFileService
    private lateinit var router: NotificationRouter
    private lateinit var service: IntroHealthService
    private lateinit var dto: MusicDto

    @BeforeEach
    fun setUp() {
        musicFileService = mockk(relaxed = true)
        router = mockk(relaxed = true)
        dto = MusicDto(
            id = introId,
            userDto = UserDto(discordId = 2L, guildId = 1L),
            fileName = "Sandstorm",
            index = 1,
        )
        every { musicFileService.getMusicFileById(introId) } returns dto
        service = IntroHealthService(musicFileService, router)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // --- plays --------------------------------------------------------------

    @Test
    fun `a play is counted and stamped`() {
        service.onIntroPlayed(IntroPlayedEvent(introId))

        assertEquals(1, dto.playCount)
        assertNotNull(dto.lastPlayedAt)
        verify { musicFileService.updateMusicFile(dto) }
    }

    @Test
    fun `a play clears an earlier failure, keeping the count consecutive`() {
        // Otherwise a link that broke once in 2024 stays flagged forever, and
        // IntroSelection keeps deprioritising a perfectly good intro.
        dto.failureCount = 1
        dto.lastFailureReason = "Video unavailable"

        service.onIntroPlayed(IntroPlayedEvent(introId))

        assertEquals(0, dto.failureCount)
        assertNull(dto.lastFailureReason)
    }

    // --- failures -----------------------------------------------------------

    @Test
    fun `a failure is recorded against the row with its reason`() {
        service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "This video is not available in your country"))

        assertEquals(1, dto.failureCount)
        assertEquals("This video is not available in your country", dto.lastFailureReason)
        assertNotNull(dto.lastFailureAt)
        verify { musicFileService.updateMusicFile(dto) }
    }

    @Test
    fun `a blank reason is still readable when it reaches the owner`() {
        service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "   "))

        assertEquals("Source could not be loaded", dto.lastFailureReason)
    }

    @Test
    fun `the owner is not told about the first failure`() {
        service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "boom"))

        verify(exactly = 0) { router.sendDm(any(), any(), any(), any()) }
    }

    @Test
    fun `the owner is told when the intro crosses into broken`() {
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "Video unavailable"))
        }

        verify(exactly = 1) {
            router.sendDm(2L, 1L, NotificationChannelKind.INTRO_BROKEN, any())
        }
    }

    @Test
    fun `the owner is told once, not on every join after that`() {
        // A broken intro fires on every single voice join. Nagging turns the
        // notification into noise faster than the fault turns into a problem.
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES + 6) {
            service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "Video unavailable"))
        }

        verify(exactly = 1) { router.sendDm(any(), any(), any(), any()) }
    }

    @Test
    fun `the DM names the intro, its slot and why it stopped`() {
        val message = slot<() -> MessageCreateData>()
        every { router.sendDm(any(), any(), any(), capture(message)) } returns Unit

        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "Video unavailable"))
        }

        val embed = message.captured().embeds.single()
        val description = embed.description.orEmpty()
        assertTrue(description.contains("Sandstorm"), description)
        assertTrue(description.contains("#1"), description)
        assertTrue(description.contains("Video unavailable"), description)
    }

    @Test
    fun `an intro with no owner does not blow up the failure path`() {
        dto.userDto = null

        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "boom"))
        }

        assertEquals(IntroHealth.UNHEALTHY_AFTER_FAILURES, dto.failureCount)
        verify(exactly = 0) { router.sendDm(any(), any(), any(), any()) }
    }

    // --- loudness -----------------------------------------------------------

    @Test
    fun `a measurement is stored so later plays can be corrected`() {
        service.onIntroLoudnessMeasured(IntroLoudnessMeasuredEvent(introId, 0.13))

        assertEquals(0.13, dto.measuredRms)
        verify { musicFileService.updateMusicFile(dto) }
    }

    // --- resilience ---------------------------------------------------------

    @Test
    fun `an intro deleted between playing and reporting is ignored`() {
        every { musicFileService.getMusicFileById(introId) } returns null

        service.onIntroPlayed(IntroPlayedEvent(introId))
        service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "boom"))
        service.onIntroLoudnessMeasured(IntroLoudnessMeasuredEvent(introId, 0.1))

        verify(exactly = 0) { musicFileService.updateMusicFile(any()) }
        verify(exactly = 0) { router.sendDm(any(), any(), any(), any()) }
    }

    @Test
    fun `a database failure never propagates into the playback path`() {
        every { musicFileService.getMusicFileById(introId) } throws IllegalStateException("connection reset")

        // No throw: these run from event handlers on the load and audio paths.
        service.onIntroPlayed(IntroPlayedEvent(introId))
        service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "boom"))
        service.onIntroLoudnessMeasured(IntroLoudnessMeasuredEvent(introId, 0.1))
    }

    @Test
    fun `a router failure does not lose the recorded failure`() {
        every { router.sendDm(any(), any(), any(), any()) } throws IllegalStateException("discord down")

        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "boom"))
        }

        assertEquals(IntroHealth.UNHEALTHY_AFTER_FAILURES, dto.failureCount)
    }

    @Test
    fun `without a router the counters still work`() {
        val unrouted = IntroHealthService(musicFileService, null)

        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            unrouted.onIntroLoadFailed(IntroLoadFailedEvent(introId, "boom"))
        }

        assertEquals(IntroHealth.UNHEALTHY_AFTER_FAILURES, dto.failureCount)
    }

    @Test
    fun `timestamps move forward across separate outcomes`() {
        val before = Instant.now().minusSeconds(1)

        service.onIntroPlayed(IntroPlayedEvent(introId))
        service.onIntroLoadFailed(IntroLoadFailedEvent(introId, "boom"))

        assertTrue(dto.lastPlayedAt!!.isAfter(before))
        assertTrue(dto.lastFailureAt!!.isAfter(before))
    }
}
