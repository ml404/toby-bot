package bot.toby.intro

import bot.toby.notify.NotificationDispatch
import bot.toby.notify.NotificationRouter
import common.notification.ChannelRouteKey
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

    /** The plans the service handed the router, with their surface builders. */
    private val dispatches = mutableListOf<NotificationDispatch>()
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
        dispatches.clear()
        val kind = slot<NotificationChannelKind>()
        val configure = slot<NotificationDispatch.() -> Unit>()
        every {
            router.dispatch(capture(kind), any<Long>(), any<Long>(), capture(configure))
        } answers { dispatches += NotificationDispatch(kind.captured).apply(configure.captured) }
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
        service.onIntroFailed(IntroFailedEvent(introId, "This video is not available in your country"))

        assertEquals(1, dto.failureCount)
        assertEquals("This video is not available in your country", dto.lastFailureReason)
        assertNotNull(dto.lastFailureAt)
        verify { musicFileService.updateMusicFile(dto) }
    }

    @Test
    fun `a blank reason is still readable when it reaches the owner`() {
        service.onIntroFailed(IntroFailedEvent(introId, "   "))

        assertEquals("Source could not be loaded", dto.lastFailureReason)
    }

    @Test
    fun `the owner is not told about the first failure`() {
        service.onIntroFailed(IntroFailedEvent(introId, "boom"))

        assertTrue(dispatches.isEmpty())
    }

    @Test
    fun `the owner is told when the intro crosses into broken`() {
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroFailed(IntroFailedEvent(introId, "Video unavailable"))
        }

        verify(exactly = 1) {
            router.dispatch(NotificationChannelKind.INTRO_BROKEN, 2L, 1L, any())
        }
    }

    @Test
    fun `the owner is told once, not on every join after that`() {
        // A broken intro fires on every single voice join. Nagging turns the
        // notification into noise faster than the fault turns into a problem.
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES + 6) {
            service.onIntroFailed(IntroFailedEvent(introId, "Video unavailable"))
        }

        assertEquals(1, dispatches.size)
    }

    @Test
    fun `it says so again if the intro is still broken much later`() {
        // The old check was an exact `== UNHEALTHY_AFTER_FAILURES`, which gave
        // the system one attempt ever. A DM that arrived while somebody was
        // away — or never arrived, because their DMs are shut — was
        // indistinguishable from never having broken.
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES + IntroHealthService.RENOTIFY_EVERY_FAILURES) {
            service.onIntroFailed(IntroFailedEvent(introId, "Video unavailable"))
        }

        assertEquals(2, dispatches.size)
    }

    @Test
    fun `the room is offered the news too, not just the owner`() {
        // Everyone else in the voice channel heard the same silence and had no
        // way to find out why. Nothing posts unless an admin has set the
        // channel — the route has no system-channel fallback.
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroFailed(IntroFailedEvent(introId, "Video unavailable"))
        }

        val plan = dispatches.single()
        assertEquals(ChannelRouteKey.INTRO_ISSUE, plan.channelPlan!!.route)
        assertTrue(plan.dmBuilder != null, "the owner's DM must not be dropped for the public post")
    }

    @Test
    fun `the public post names the intro and the reason, not the fix-it steps`() {
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroFailed(IntroFailedEvent(introId, "Video unavailable"))
        }

        val description = dispatches.single().channelPlan!!.message().embeds.single().description.orEmpty()
        assertTrue(description.contains("Sandstorm"), description)
        assertTrue(description.contains("Video unavailable"), description)
        // Replacing the link is the owner's job, and their DM says so.
        assertTrue(!description.contains("/setintro"), description)
    }

    @Test
    fun `the DM names the intro, its slot and why it stopped`() {
        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroFailed(IntroFailedEvent(introId, "Video unavailable"))
        }

        val embed = dispatches.single().dmBuilder!!().embeds.single()
        val description = embed.description.orEmpty()
        assertTrue(description.contains("Sandstorm"), description)
        assertTrue(description.contains("#1"), description)
        assertTrue(description.contains("Video unavailable"), description)
    }

    @Test
    fun `an intro with no owner does not blow up the failure path`() {
        dto.userDto = null

        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroFailed(IntroFailedEvent(introId, "boom"))
        }

        assertEquals(IntroHealth.UNHEALTHY_AFTER_FAILURES, dto.failureCount)
        assertTrue(dispatches.isEmpty())
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
        service.onIntroFailed(IntroFailedEvent(introId, "boom"))
        service.onIntroLoudnessMeasured(IntroLoudnessMeasuredEvent(introId, 0.1))

        verify(exactly = 0) { musicFileService.updateMusicFile(any()) }
        assertTrue(dispatches.isEmpty())
    }

    @Test
    fun `a database failure never propagates into the playback path`() {
        every { musicFileService.getMusicFileById(introId) } throws IllegalStateException("connection reset")

        // No throw: these run from event handlers on the load and audio paths.
        service.onIntroPlayed(IntroPlayedEvent(introId))
        service.onIntroFailed(IntroFailedEvent(introId, "boom"))
        service.onIntroLoudnessMeasured(IntroLoudnessMeasuredEvent(introId, 0.1))
    }

    @Test
    fun `a router failure does not lose the recorded failure`() {
        every {
            router.dispatch(any<NotificationChannelKind>(), any<Long>(), any<Long>(), any())
        } throws IllegalStateException("discord down")

        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            service.onIntroFailed(IntroFailedEvent(introId, "boom"))
        }

        assertEquals(IntroHealth.UNHEALTHY_AFTER_FAILURES, dto.failureCount)
    }

    @Test
    fun `without a router the counters still work`() {
        val unrouted = IntroHealthService(musicFileService, null)

        repeat(IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            unrouted.onIntroFailed(IntroFailedEvent(introId, "boom"))
        }

        assertEquals(IntroHealth.UNHEALTHY_AFTER_FAILURES, dto.failureCount)
    }

    @Test
    fun `timestamps move forward across separate outcomes`() {
        val before = Instant.now().minusSeconds(1)

        service.onIntroPlayed(IntroPlayedEvent(introId))
        service.onIntroFailed(IntroFailedEvent(introId, "boom"))

        assertTrue(dto.lastPlayedAt!!.isAfter(before))
        assertTrue(dto.lastFailureAt!!.isAfter(before))
    }
}
