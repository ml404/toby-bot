package bot.toby.lavaplayer

import bot.toby.intro.IntroFailedEvent
import bot.toby.intro.IntroPlayedEvent
import bot.toby.intro.SourceOutageNoticedEvent
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

    /**
     * A transient failure that doesn't *name* a block — these tests are about
     * the retry ladder, and a message matching [common.media.SourceOutage]'s
     * markers would declare an outage on the first failure and change what
     * the branches below do.
     */
    private fun transientFailure() =
        FriendlyException("Something broke while looking up the track", FriendlyException.Severity.SUSPICIOUS, RuntimeException("x"))

    private fun blockFailure() =
        FriendlyException("Sign in to confirm you're not a bot", FriendlyException.Severity.SUSPICIOUS, RuntimeException("x"))

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
    fun `a load on its own is not yet a play`() {
        // A load only proves the source will talk to us about the track. In the
        // incident that prompted this, every load succeeded and every stream
        // then died — and because a play clears the failure counter, calling
        // the load a play erased the evidence on every single attempt.
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().trackLoaded(mockk(relaxed = true))

        assertTrue(published.filterIsInstance<IntroPlayedEvent>().isEmpty())
    }

    @Test
    fun `a play is reported once audio has actually run`() {
        val published = captureEvents()

        pm.reportPlaybackSuccess("1_2_1", uninterrupted = true)

        assertEquals(listOf(IntroPlayedEvent("1_2_1")), published.filterIsInstance<IntroPlayedEvent>())
    }

    @Test
    fun `a play that was cut short still counts against the row`() {
        val published = captureEvents()

        pm.reportPlaybackSuccess("1_2_1", uninterrupted = false)

        assertEquals(listOf(IntroPlayedEvent("1_2_1")), published.filterIsInstance<IntroPlayedEvent>())
    }

    @Test
    fun `only an uninterrupted play ends a declared outage`() {
        // A track cut short was very likely streaming down a connection opened
        // before the trouble started, so it is no evidence a fresh request
        // would work. Intro preemption cuts one short on every voice join.
        val published = captureEvents()
        repeat(3) { i -> failIntro("1_2_$i", "url-$i") }
        assertEquals(2, published.filterIsInstance<IntroFailedEvent>().size)

        pm.reportPlaybackSuccess(null, uninterrupted = false)
        failIntro("1_9_1", "url-still-dead")
        assertEquals(2, published.filterIsInstance<IntroFailedEvent>().size)

        pm.reportPlaybackSuccess(null, uninterrupted = true)
        failIntro("1_9_2", "url-really-dead")
        assertEquals(3, published.filterIsInstance<IntroFailedEvent>().size)
    }

    @Test
    fun `a track that was nobody's intro records no play`() {
        // Ordinary queued music runs through the same report; there is just no
        // row for it to count against.
        val published = captureEvents()

        pm.reportPlaybackSuccess(null, uninterrupted = true)

        assertTrue(published.filterIsInstance<IntroPlayedEvent>().isEmpty())
    }

    @Test
    fun `a link that resolves to nothing is reported as a failure`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().noMatches()

        val failure = published.filterIsInstance<IntroFailedEvent>().single()
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

        val failure = published.filterIsInstance<IntroFailedEvent>().single()
        assertEquals("This video is not available in your country", failure.reason)
    }

    @Test
    fun `a failure with no message still carries something readable`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")
        handlers.last().loadFailed(FriendlyException(null, FriendlyException.Severity.COMMON, RuntimeException("x")))

        assertEquals("Source could not be loaded", published.filterIsInstance<IntroFailedEvent>().single().reason)
    }

    @Test
    fun `a retried failure is only reported once the retries are exhausted`() {
        // Otherwise every rate-limited night would mark healthy intros broken.
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, "1_2_1")

        repeat(PlayerManager.MAX_LOAD_ATTEMPTS - 1) {
            handlers.last().loadFailed(transientFailure())
            assertTrue(published.filterIsInstance<IntroFailedEvent>().isEmpty(), "reported mid-retry")
        }
        handlers.last().loadFailed(transientFailure())

        assertEquals(1, published.filterIsInstance<IntroFailedEvent>().size)
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

        assertTrue(published.none { it is IntroPlayedEvent || it is IntroFailedEvent }, published.toString())
    }

    @Test
    fun `an intro played without a known row reports nothing rather than guessing`() {
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url", 5, 0L, 50, null, null)
        handlers.last().trackLoaded(mockk(relaxed = true))
        handlers.last().noMatches()

        assertTrue(published.none { it is IntroPlayedEvent || it is IntroFailedEvent })
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

    // --- when the source is refusing us, not the track --------------------------
    //
    // A rate-limited or IP-blocked bot fails *every* load. Counting those
    // against intro health marks every intro on every server broken within two
    // joins and DMs each owner advice to replace a link that is perfectly
    // fine — and a blocked lookup usually returns "no matches", so `/play`
    // spends the outage telling people their working links don't exist.

    private fun failIntro(introId: String, url: String, exception: FriendlyException? = null) {
        pm.loadAndPlayIntro(guild, null, url, 5, 0L, 50, null, introId)
        if (exception == null) handlers.last().noMatches() else handlers.last().loadFailed(exception)
    }

    /** Drives a block failure through the retry ladder to its terminal branch. */
    private fun failIntroWithBlock(introId: String, url: String) {
        pm.loadAndPlayIntro(guild, null, url, 5, 0L, 50, null, introId)
        repeat(PlayerManager.MAX_LOAD_ATTEMPTS) { handlers.last().loadFailed(blockFailure()) }
    }

    @Test
    fun `a failure that names a block is not counted against the intro`() {
        val published = captureEvents()

        failIntroWithBlock("1_2_1", "url-a")

        assertTrue(
            published.filterIsInstance<IntroFailedEvent>().isEmpty(),
            "a blocked load says nothing about whether this intro works",
        )
    }

    @Test
    fun `a block is still retried before it is believed`() {
        // The retry ladder runs first either way: a 429 that clears on the
        // second attempt should never have been called an outage at all.
        val published = captureEvents()

        pm.loadAndPlayIntro(guild, null, "url-a", 5, 0L, 50, null, "1_2_1")
        handlers.last().loadFailed(blockFailure())
        handlers.last().trackLoaded(realTrack())
        pm.reportPlaybackSuccess("1_2_1", uninterrupted = true)

        assertTrue(published.filterIsInstance<IntroFailedEvent>().isEmpty())
        assertEquals(1, published.filterIsInstance<IntroPlayedEvent>().size)
    }

    @Test
    fun `once several sources fail together, intro failures stop being counted`() {
        val published = captureEvents()

        // The first two look like ordinary dead links and are reported as such.
        failIntro("1_2_1", "url-a")
        failIntro("1_2_2", "url-b")
        assertEquals(2, published.filterIsInstance<IntroFailedEvent>().size)

        // The third makes it a pattern, and everything after it is the
        // outage's fault rather than the intro's.
        failIntro("1_2_3", "url-c")
        failIntro("1_3_1", "url-d")

        assertEquals(2, published.filterIsInstance<IntroFailedEvent>().size)
    }

    @Test
    fun `one genuinely dead link is still reported`() {
        // The expensive false negative: an outage that swallowed real failures
        // would leave broken intros silently broken forever, which is the
        // problem intro health exists to solve.
        val published = captureEvents()

        failIntro(
            "1_2_1", "url-a",
            FriendlyException("This video is unavailable", FriendlyException.Severity.COMMON, RuntimeException("x")),
        )

        assertEquals(1, published.filterIsInstance<IntroFailedEvent>().size)
    }

    @Test
    fun `the same dead intro failing on repeat never suppresses its own reporting`() {
        // Keyed by source, so a single bad link retried on every voice join
        // can't pass itself off as an outage.
        val published = captureEvents()

        repeat(5) { failIntro("1_2_1", "url-a") }

        assertEquals(5, published.filterIsInstance<IntroFailedEvent>().size)
    }

    @Test
    fun `a successful playback puts intro reporting straight back`() {
        val published = captureEvents()
        // The third distinct failure is the one that reveals the pattern, so
        // it counts as the outage's too — only the first two are reported.
        repeat(3) { i -> failIntro("1_2_$i", "url-$i") }
        failIntro("1_3_1", "url-suppressed")
        assertEquals(2, published.filterIsInstance<IntroFailedEvent>().size)

        // Audio actually running is what ends an outage now — a load that
        // succeeds and then produces nothing was exactly the shape of the
        // failure, so it can no longer be the thing that clears the window.
        pm.reportPlaybackSuccess("1_4_1", uninterrupted = true)

        failIntro("1_5_1", "url-really-dead")
        assertEquals(3, published.filterIsInstance<IntroFailedEvent>().size)
    }

    @Test
    fun `a guild is told, once, that the source is refusing us`() {
        // During an outage this path runs on every single voice join, so the
        // latch is the whole point — the alternative is the same notice over
        // and over while nobody's intro works.
        val published = captureEvents()

        repeat(3) { i -> failIntro("1_2_$i", "url-$i") }
        failIntro("1_9_1", "url-more")
        failIntro("1_9_2", "url-more-still")

        val notices = published.filterIsInstance<SourceOutageNoticedEvent>()
        assertEquals(listOf(SourceOutageNoticedEvent(1L)), notices)
    }

    @Test
    fun `nothing is announced while the source is behaving`() {
        // Two failures look like two dead links, which is a different message
        // and the owner's business.
        val published = captureEvents()

        failIntro("1_2_1", "url-a")
        failIntro("1_2_2", "url-b")

        assertTrue(published.filterIsInstance<SourceOutageNoticedEvent>().isEmpty())
    }

    @Test
    fun `every guild that feels it gets told`() {
        // The outage is bot-wide, but the notice is per-server: a guild with
        // nobody in voice has no silence to explain.
        val published = captureEvents()

        repeat(3) { i -> failIntro("1_2_$i", "url-$i") }
        failIntro("2_5_1", "url-other-guild")

        assertEquals(
            listOf(SourceOutageNoticedEvent(1L), SourceOutageNoticedEvent(2L)),
            published.filterIsInstance<SourceOutageNoticedEvent>(),
        )
    }

    @Test
    fun `a second episode is announced rather than swallowed as a repeat`() {
        val published = captureEvents()
        repeat(3) { i -> failIntro("1_2_$i", "url-$i") }
        assertEquals(1, published.filterIsInstance<SourceOutageNoticedEvent>().size)

        // Audio playing is what ends an episode, so it also re-arms the notice.
        pm.reportPlaybackSuccess(null, uninterrupted = true)
        repeat(3) { i -> failIntro("1_3_$i", "url-later-$i") }

        assertEquals(2, published.filterIsInstance<SourceOutageNoticedEvent>().size)
    }

    @Test
    fun `during an outage a listener is told it isn't their link`() {
        // "Nothing found for the link" is a lie in this state, and the one the
        // bot told everybody all evening.
        pm.loadAndPlay(guild, event, "url-a", true, 5, 0L, 50, null)
        repeat(PlayerManager.MAX_LOAD_ATTEMPTS) { handlers.last().loadFailed(blockFailure()) }

        val messages = mutableListOf<String>()
        every { event.hook.sendMessage(capture(messages)) } returns mockk(relaxed = true)

        pm.loadAndPlay(guild, event, "url-b", true, 5, 0L, 50, null)
        handlers.last().noMatches()

        assertTrue(messages.any { it == PlayerManager.OUTAGE_MESSAGE }, messages.toString())
        assertTrue(messages.none { it.contains("Nothing found") }, messages.toString())
    }

    @Test
    fun `an ordinary dead link still names the link`() {
        val messages = mutableListOf<String>()
        every { event.hook.sendMessage(capture(messages)) } returns mockk(relaxed = true)

        pm.loadAndPlay(guild, event, "url-a", true, 5, 0L, 50, null)
        handlers.last().noMatches()

        assertTrue(messages.any { it.contains("Nothing found for the link 'url-a'") }, messages.toString())
    }
}
