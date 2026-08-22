package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the scheduler reports about a track once it has actually run.
 *
 * The gap these cover: `onTrackException` was never overridden, so a source
 * that resolved and then died while streaming produced silence and left no
 * trace — nothing in the channel, nothing against the intro's health, nothing
 * for the outage correlation. And because a *load* was being called a play,
 * each attempt reset the failure counter that was meant to catch it, so the
 * same intro could fail forever without ever crossing the threshold.
 */
class TrackSchedulerPlaybackOutcomeTest {

    private val player: AudioPlayer = mockk(relaxed = true)
    private lateinit var reporter: RecordingReporter
    private lateinit var scheduler: TrackScheduler

    private class RecordingReporter : PlaybackOutcomeReporter {
        val plays = mutableListOf<String?>()
        val failures = mutableListOf<Triple<String?, String, String?>>()

        val outageClearing = mutableListOf<String?>()

        override fun playbackSucceeded(introId: String?, uninterrupted: Boolean) {
            plays += introId
            if (uninterrupted) outageClearing += introId
        }

        override fun playbackFailed(introId: String?, sourceKey: String, reason: String?) {
            failures += Triple(introId, sourceKey, reason)
        }
    }

    @BeforeEach
    fun setUp() {
        reporter = RecordingReporter()
        scheduler = TrackScheduler(player, guildId = 1L, deleteDelay = 5, outcomeReporter = reporter)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun track(identifier: String = "UURf_6KI_Rk"): AudioTrack = mockk(relaxed = true) {
        every { info } returns AudioTrackInfo("Some Track", "Some Author", 60_000L, identifier, false, "http://x")
        every { this@mockk.identifier } returns identifier
        every { userData } returns 50
        every { makeClone() } returns this@mockk
    }

    private fun streamDied(message: String = "Something broke when playing the track.") =
        FriendlyException(message, FriendlyException.Severity.SUSPICIOUS, java.io.IOException("400"))

    // --- the stream dying mid-track -------------------------------------

    @Test
    fun `a stream that dies is reported against the intro it was playing`() {
        val intro = track()
        scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")

        scheduler.onTrackException(player, intro, streamDied())

        assertEquals(1, reporter.failures.size)
        assertEquals("1_2_1", reporter.failures.single().first)
    }

    @Test
    fun `the failure is keyed on the track, not the guild`() {
        // The outage correlation counts *distinct sources* failing inside a
        // window, which is what tells one dead video apart from the host
        // refusing everything. Keying it on anything guild-shaped would make
        // every failure look like the same one.
        val intro = track(identifier = "UURf_6KI_Rk")
        scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")

        scheduler.onTrackException(player, intro, streamDied())

        assertEquals("UURf_6KI_Rk", reporter.failures.single().second)
    }

    @Test
    fun `the reason the source gave is carried through`() {
        val queued = track()

        scheduler.onTrackException(player, queued, streamDied("Invalid status code for player api response: 400"))

        assertEquals("Invalid status code for player api response: 400", reporter.failures.single().third)
    }

    @Test
    fun `a failed stream is not also counted as a play`() {
        // The two together are the bug: an attempt that made no sound used to
        // arrive as a success and clear the failure counter behind itself.
        val intro = track()
        scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")

        scheduler.onTrackStart(player, intro)
        scheduler.onTrackException(player, intro, streamDied())
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.LOAD_FAILED)

        assertEquals(1, reporter.failures.size)
        assertTrue(reporter.plays.isEmpty(), reporter.plays.toString())
    }

    @Test
    fun `queued music that dies is reported with no intro attached`() {
        val queued = track()

        scheduler.onTrackException(player, queued, streamDied())

        assertEquals(null, reporter.failures.single().first)
    }

    @Test
    fun `it does not stop the player, which would take the resume slot with it`() {
        // lavaplayer ends the track itself with LOAD_FAILED, whose mayStartNext
        // is true, so the queue advances on its own. Stopping here — the way
        // onTrackStuck has to — would consume the resume slot and lose the
        // music an intro interrupted.
        val intro = track()
        scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")

        scheduler.onTrackException(player, intro, streamDied())

        verify(exactly = 0) { player.stopTrack() }
    }

    @Test
    fun `a track somebody asked for is reported in the channel`() {
        val channel = mockk<MessageChannelUnion>(relaxed = true)
        val send = mockk<MessageCreateAction>(relaxed = true)
        val messages = mutableListOf<String>()
        every { channel.sendMessage(capture(messages)) } returns send
        scheduler.event = mockk<SlashCommandInteractionEvent>(relaxed = true) {
            every { this@mockk.channel } returns channel
        }

        scheduler.onTrackException(player, track(), streamDied("the source gave up"))

        assertTrue(messages.single().contains("Some Track"), messages.single())
        assertTrue(messages.single().contains("the source gave up"), messages.single())
    }

    @Test
    fun `an intro says nothing in the channel, because nobody asked for it`() {
        // Intros play from the voice-join path, where event is null and there
        // is no conversation to interrupt. Their owner hears about it by DM
        // once the failures add up.
        val intro = track()
        scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")
        scheduler.event = null

        scheduler.onTrackException(player, intro, streamDied())

        // Reported for health and outage purposes all the same.
        assertEquals(1, reporter.failures.size)
    }

    // --- what actually counts as a play ---------------------------------

    @Test
    fun `an intro that runs to the end counts as a play`() {
        val intro = track()
        scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")

        scheduler.onTrackStart(player, intro)
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.FINISHED)

        assertEquals(listOf("1_2_1"), reporter.plays)
    }

    @Test
    fun `a clipped intro stopped by its own marker still counts as a play`() {
        // STOPPED is how every clipped intro ends — the marker stops it at the
        // clip boundary. Treating that as anything but a play would mark every
        // trimmed intro on the server as never having worked.
        val intro = track()
        scheduler.queueIntro(intro, 0L, 5_000L, 50, null, "1_2_1")

        scheduler.onTrackStart(player, intro)
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.STOPPED)

        assertEquals(listOf("1_2_1"), reporter.plays)
    }

    @Test
    fun `ordinary music playing through reports a play with no intro id`() {
        // It still ends an outage: audio reaching a listener is the proof,
        // whoever queued it.
        val queued = track()

        scheduler.onTrackStart(player, queued)
        scheduler.onTrackEnd(player, queued, AudioTrackEndReason.FINISHED)

        assertEquals(listOf<String?>(null), reporter.plays)
    }

    @Test
    fun `a stream that died reports no play`() {
        val queued = track()

        scheduler.onTrackStart(player, queued)
        scheduler.onTrackException(player, queued, streamDied())
        scheduler.onTrackEnd(player, queued, AudioTrackEndReason.LOAD_FAILED)

        assertTrue(reporter.plays.isEmpty())
    }

    @Test
    fun `an intro that plays briefly and then dies is not counted as a play`() {
        // The trap the end reason sets: lavaplayer reports LOAD_FAILED only
        // when the stream died before ANY frames arrived. Half a second of
        // audio and then a break comes back as FINISHED — the same reason a
        // clean play gets — so gating on the end reason would have let this
        // erase the failure recorded a moment earlier.
        val intro = track()
        scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")

        scheduler.onTrackStart(player, intro)
        scheduler.onTrackException(player, intro, streamDied())
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.FINISHED)

        assertEquals(1, reporter.failures.size)
        assertTrue(reporter.plays.isEmpty(), reporter.plays.toString())
    }

    @Test
    fun `a track cut short still counts as a play but does not end an outage`() {
        // Intro preemption ends the music with REPLACED every time somebody
        // joins voice. It was audible, so it counts against the row — but a
        // track already streaming says nothing about whether a fresh request
        // would work, and accepting it as proof would clear the outage window
        // over and over on exactly the busiest servers.
        val queued = track()

        scheduler.onTrackStart(player, queued)
        scheduler.onTrackEnd(player, queued, AudioTrackEndReason.REPLACED)

        assertEquals(listOf<String?>(null), reporter.plays)
        assertTrue(reporter.outageClearing.isEmpty(), reporter.outageClearing.toString())
    }

    @Test
    fun `a track that ran to its own end does end an outage`() {
        val queued = track()

        scheduler.onTrackStart(player, queued)
        scheduler.onTrackEnd(player, queued, AudioTrackEndReason.FINISHED)

        assertEquals(listOf<String?>(null), reporter.outageClearing)
    }

    @Test
    fun `a looping queue does not restart a track that just died`() {
        // LOAD_FAILED also says mayStartNext, so looping would otherwise
        // restart the clone immediately, forever, with no backoff — hammering
        // the very API that is already refusing us.
        val queued = track()
        scheduler.isLooping = true

        scheduler.onTrackStart(player, queued)
        scheduler.onTrackException(player, queued, streamDied())
        scheduler.onTrackEnd(player, queued, AudioTrackEndReason.LOAD_FAILED)

        verify(exactly = 0) { player.startTrack(any(), false) }
    }

    @Test
    fun `a looping queue still repeats a track that played`() {
        val queued = track()
        scheduler.isLooping = true

        scheduler.onTrackStart(player, queued)
        scheduler.onTrackEnd(player, queued, AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { player.startTrack(any(), false) }
    }

    @Test
    fun `the same intro failing repeatedly accumulates instead of resetting`() {
        // The whole point. Before this, each attempt's load was reported as a
        // play, which cleared the counter, so an intro failing this way could
        // never reach the threshold that DMs its owner.
        repeat(3) {
            val intro = track()
            scheduler.queueIntro(intro, 0L, null, 50, null, "1_2_1")
            scheduler.onTrackStart(player, intro)
            scheduler.onTrackException(player, intro, streamDied())
            scheduler.onTrackEnd(player, intro, AudioTrackEndReason.LOAD_FAILED)
        }

        assertEquals(3, reporter.failures.size)
        assertTrue(reporter.plays.isEmpty(), reporter.plays.toString())
    }
}
