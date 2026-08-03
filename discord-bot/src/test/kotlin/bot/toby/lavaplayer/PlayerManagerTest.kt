package bot.toby.lavaplayer

import bot.toby.intro.IntroLoadFailedEvent
import bot.toby.intro.IntroPlayedEvent
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.concurrent.ScheduledExecutorService

class PlayerManagerTest {

    private lateinit var apm: AudioPlayerManager
    private lateinit var guild: Guild
    private lateinit var event: SlashCommandInteractionEvent
    private val handlers = mutableListOf<AudioLoadResultHandler>()
    private lateinit var pm: PlayerManager

    @BeforeEach
    fun setUp() {
        apm = mockk(relaxed = true)
        handlers.clear()
        val handlerSlot = slot<AudioLoadResultHandler>()
        every { apm.loadItemOrdered(any<Any>(), any<String>(), capture(handlerSlot)) } answers {
            handlers.add(handlerSlot.captured)
            mockk<java.util.concurrent.Future<Void>>(relaxed = true)
        }
        // Run scheduled retries synchronously so the test is deterministic.
        val immediate = mockk<ScheduledExecutorService>()
        every { immediate.schedule(any<Runnable>(), any<Long>(), any<java.util.concurrent.TimeUnit>()) } answers {
            firstArg<Runnable>().run()
            mockk<java.util.concurrent.ScheduledFuture<*>>(relaxed = true)
        }
        pm = PlayerManager(apm, immediate)
        guild = mockk(relaxed = true) { every { idLong } returns 1L }
        event = mockk(relaxed = true)
    }

    private fun transientFailure() =
        FriendlyException("rate limited", FriendlyException.Severity.SUSPICIOUS, RuntimeException("x"))

    @Test
    fun `transient load failures are retried up to the attempt cap`() {
        pm.loadAndPlay(guild, event, "url", true, 5, 0L, 50, null)
        assertEquals(1, handlers.size)

        handlers.last().loadFailed(transientFailure())
        assertEquals(2, handlers.size, "first transient failure should trigger a retry")

        handlers.last().loadFailed(transientFailure())
        assertEquals(3, handlers.size, "second transient failure should trigger another retry")

        handlers.last().loadFailed(transientFailure())
        assertEquals(3, handlers.size, "at the cap no further load is attempted")

        verify(exactly = PlayerManager.MAX_LOAD_ATTEMPTS) { apm.loadItemOrdered(any<Any>(), any<String>(), any<AudioLoadResultHandler>()) }
    }

    @Test
    fun `common track-specific failures are not retried`() {
        pm.loadAndPlay(guild, event, "url", true, 5, 0L, 50, null)

        handlers.last().loadFailed(
            FriendlyException("video unavailable", FriendlyException.Severity.COMMON, RuntimeException("x")),
        )

        assertEquals(1, handlers.size)
        verify(exactly = 1) { apm.loadItemOrdered(any<Any>(), any<String>(), any<AudioLoadResultHandler>()) }
    }

    // --- intro outcomes -----------------------------------------------------
    //
    // Both failure branches below used to write to `event?.hook`, and `event`
    // is always null on the intro path (voice joins have no interaction). A
    // dead link therefore produced silence, forever, with nothing recorded.

    /** Captures what the audio layer published to the Spring bus. */
    private fun captureEvents(): MutableList<Any> {
        val published = mutableListOf<Any>()
        SchedulerEvents.publisher = ApplicationEventPublisher { event -> published.add(event) }
        return published
    }

    @AfterEach
    fun clearPublisher() {
        SchedulerEvents.publisher = null
    }

    @Test
    fun `a loaded intro is reported as played, against its row`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().trackLoaded(mockk(relaxed = true))

        assertEquals(listOf(IntroPlayedEvent("1_2_1")), published.filterIsInstance<IntroPlayedEvent>())
    }

    @Test
    fun `a link that resolves to nothing is reported as a failure`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().noMatches()

        val failure = published.filterIsInstance<IntroLoadFailedEvent>().single()
        assertEquals("1_2_1", failure.introId)
        assertEquals("Nothing found at this link", failure.reason)
    }

    @Test
    fun `a permanent load failure is reported with the reason the source gave`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().loadFailed(
            FriendlyException(
                "This video is not available in your country",
                FriendlyException.Severity.COMMON,
                RuntimeException("x"),
            ),
        )

        val failure = published.filterIsInstance<IntroLoadFailedEvent>().single()
        assertEquals("This video is not available in your country", failure.reason)
    }

    @Test
    fun `a failure with no message still carries something readable`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().loadFailed(FriendlyException(null, FriendlyException.Severity.COMMON, RuntimeException("x")))

        assertEquals("Source could not be loaded", published.filterIsInstance<IntroLoadFailedEvent>().single().reason)
    }

    @Test
    fun `a retried failure is only reported once the retries are exhausted`() {
        // Otherwise every rate-limited night would mark healthy intros broken.
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")

        repeat(PlayerManager.MAX_LOAD_ATTEMPTS - 1) {
            handlers.last().loadFailed(transientFailure())
            assertTrue(published.filterIsInstance<IntroLoadFailedEvent>().isEmpty(), "reported mid-retry")
        }
        handlers.last().loadFailed(transientFailure())

        assertEquals(1, published.filterIsInstance<IntroLoadFailedEvent>().size)
    }

    /** TrackInfoMapper reads AudioTrackInfo's fields, which mockk can't stub. */
    private fun realTrack() = mockk<com.sedmelluq.discord.lavaplayer.track.AudioTrack>(relaxed = true) {
        every { info } returns AudioTrackInfo("t", "a", 60_000L, "identifier", false, "http://example.com")
        every { userData } returns 50
    }

    @Test
    fun `regular playback reports no intro outcome at all`() {
        val published = captureEvents()

        pm.loadAndPlay(guild, event, "url", true, 5, 0L, 50, null)
        handlers.last().trackLoaded(realTrack())
        handlers.last().noMatches()

        assertTrue(published.none { it is IntroPlayedEvent || it is IntroLoadFailedEvent }, published.toString())
    }

    @Test
    fun `an intro played without a known row reports nothing rather than guessing`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, null)
        handlers.last().trackLoaded(mockk(relaxed = true))
        handlers.last().noMatches()

        assertTrue(published.none { it is IntroPlayedEvent || it is IntroLoadFailedEvent })
    }

    @Test
    fun `the intro row id reaches the scheduler so the loudness meter can find it`() {
        val musicManager = pm.getMusicManager(guild)
        val track = mockk<com.sedmelluq.discord.lavaplayer.track.AudioTrack>(relaxed = true)

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_3")
        handlers.last().trackLoaded(track)

        assertEquals("1_2_3", musicManager.scheduler.introIdFor(track))
    }

    @Test
    fun `no publisher wired means the outcome is dropped, not thrown`() {
        // Unit tests and early startup run without the Spring bridge.
        SchedulerEvents.publisher = null

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().noMatches()
    }
}
