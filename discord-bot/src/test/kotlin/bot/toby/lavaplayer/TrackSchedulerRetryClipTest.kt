package bot.toby.lavaplayer

import bot.toby.helpers.MusicPlayerHelper
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import com.sedmelluq.discord.lavaplayer.track.TrackMarkerHandler
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What a second attempt at an intro is actually given to play.
 *
 * `makeClone` copies a track's identity and nothing the scheduler did to it:
 * the clone arrives at position zero with no marker on it. The retry used to
 * copy its bookkeeping out of the per-track maps, but `onTrackEnd` clears
 * those at the top and the retry runs near the bottom, so every copy read an
 * entry that had just been removed and quietly did nothing. Five seconds of
 * somebody's intro came back as the whole four-minute track, with its
 * now-playing message held open for the length of it.
 */
class TrackSchedulerRetryClipTest {

    private val player: AudioPlayer = mockk(relaxed = true)
    private lateinit var scheduler: TrackScheduler

    private object AlwaysRetry : PlaybackOutcomeReporter {
        override fun playbackSucceeded(introId: String?, uninterrupted: Boolean) = Unit
        override fun playbackFailed(introId: String?, sourceKey: String, reason: String?) = true
    }

    @BeforeEach
    fun setUp() {
        every { player.playingTrack } returns null
        every { player.startTrack(any(), any()) } returns true
        scheduler = TrackScheduler(player, guildId = 1L, deleteDelay = 5, outcomeReporter = AlwaysRetry)
        mockkObject(MusicPlayerHelper)
        every { MusicPlayerHelper.nowPlaying(any(), any(), any(), any(), any()) } just Runs
        every { MusicPlayerHelper.resetMessages(any()) } just Runs
        val guild = mockk<Guild>(relaxed = true) { every { idLong } returns 1L }
        scheduler.event = mockk<SlashCommandInteractionEvent>(relaxed = true) { every { this@mockk.guild } returns guild }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun track(name: String, volume: Int = 50): AudioTrack = mockk(relaxed = true) {
        every { info } returns AudioTrackInfo(name, "Author", 240_000L, "vid", false, "http://x")
        every { identifier } returns "vid"
        every { userData } returns volume
    }

    private fun streamDied() =
        FriendlyException("broke", FriendlyException.Severity.SUSPICIOUS, java.io.IOException("400"))

    /** Runs an intro through its first death, returning the clone the retry started. */
    private fun retryAfterDeath(startMs: Long, endMs: Long?): AudioTrack {
        val intro = track("Intro")
        val clone = track("Intro (second go)")
        every { intro.makeClone() } returns clone

        scheduler.queueIntro(intro, startMs, endMs, 50, 99L, "1_2_1")
        scheduler.onTrackException(player, intro, streamDied())
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.FINISHED)

        verify { player.startTrack(clone, false) }
        return clone
    }

    @Test
    fun `the second attempt starts where the first was told to`() {
        val clone = retryAfterDeath(startMs = 3_000L, endMs = 8_000L)

        // Left at zero, a retried intro opens on whatever the track begins
        // with rather than the bit that was chosen.
        verify { clone.position = 3_000L }
    }

    @Test
    fun `the second attempt is clipped like the first, so it still ends`() {
        val clone = retryAfterDeath(startMs = 3_000L, endMs = 8_000L)

        val marker = slot<TrackMarker>()
        verify { clone.setMarker(capture(marker)) }
        assertEquals(8_000L, marker.captured.timecode)

        // And the marker does what the original's did: stops through the
        // player, which is what tears the now-playing message down.
        marker.captured.handler.handle(TrackMarkerHandler.MarkerState.REACHED)
        verify { player.stopTrack() }
    }

    @Test
    fun `an intro with no clip end gets no marker, but still its start`() {
        val clone = retryAfterDeath(startMs = 2_000L, endMs = null)

        verify { clone.position = 2_000L }
        verify(exactly = 0) { clone.setMarker(any()) }
    }

    @Test
    fun `the second attempt keeps the volume the intro was set to`() {
        val clone = retryAfterDeath(startMs = 0L, endMs = 8_000L)

        verify { clone.userData = 50 }
    }

    @Test
    fun `the second attempt keeps who asked for it`() {
        val clone = retryAfterDeath(startMs = 0L, endMs = 8_000L)

        // The now-playing footer names them, and it is rendered again from the
        // clone's own onTrackStart.
        assertEquals(99L, scheduler.getRequesterId(clone))
    }

    @Test
    fun `the second attempt keeps the length the fade aims at`() {
        val clone = retryAfterDeath(startMs = 3_000L, endMs = 8_000L)

        assertEquals(5_000L, scheduler.introPlaybackMsFor(clone))
    }

    @Test
    fun `a second attempt that also dies tears the now-playing message down`() {
        val clone = retryAfterDeath(startMs = 0L, endMs = 8_000L)

        scheduler.onTrackException(player, clone, streamDied())
        scheduler.onTrackEnd(player, clone, AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { MusicPlayerHelper.resetMessages(1L) }
    }

    @Test
    fun `a second attempt that reaches its clip end tears the now-playing message down`() {
        val clone = retryAfterDeath(startMs = 0L, endMs = 8_000L)

        // How every clipped intro ends: its own marker stops the player.
        scheduler.onTrackEnd(player, clone, AudioTrackEndReason.STOPPED)

        verify(exactly = 1) { MusicPlayerHelper.resetMessages(1L) }
    }

    @Test
    fun `a preempted track comes back still clipped`() {
        // Same mistake, one step away: the resume clone was handed the clip
        // bounds but not a marker, so a clipped song an intro interrupted came
        // back with nothing to stop it.
        val playing = track("Song")
        val resume = track("Song (resumed)")
        every { playing.makeClone() } returns resume
        every { player.playingTrack } returns null
        scheduler.queue(playing, 1_000L, 30_000L, 60)

        every { player.playingTrack } returns playing
        scheduler.queueIntro(track("Intro"), 0L, 5_000L, 50, null, "1_2_1")

        val marker = slot<TrackMarker>()
        verify { resume.setMarker(capture(marker)) }
        assertEquals(30_000L, marker.captured.timecode)
    }
    @Test
    fun `an intro played on a voice join still tears the message down afterwards`() {
        // A voice-join intro loads with no interaction behind it. Teardown used
        // to read the guild off that interaction, so from the moment one
        // played, nothing could remove the now-playing message any more.
        scheduler.event = null

        scheduler.onTrackEnd(player, track("Song"), AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { MusicPlayerHelper.resetMessages(1L) }
    }

    @Test
    fun `the clip a preempted track comes back with is the one that stops it`() {
        // The marker being present is not the point — firing it is. This is
        // the whole reason the stop goes through the player: onTrackEnd is
        // what tears down the now-playing message and restores the volume.
        val playing = track("Song")
        val resume = track("Song (resumed)")
        every { playing.makeClone() } returns resume
        scheduler.queue(playing, 1_000L, 30_000L, 60)
        every { player.playingTrack } returns playing
        scheduler.queueIntro(track("Intro"), 0L, 5_000L, 50, null, "1_2_1")

        val marker = slot<TrackMarker>()
        verify { resume.setMarker(capture(marker)) }
        marker.captured.handler.handle(TrackMarkerHandler.MarkerState.REACHED)

        verify { player.stopTrack() }
    }

    @Test
    fun `an intro handing the music back does not take the now-playing message with it`() {
        // The resumed track's own onTrackStart edits the message in place, so
        // tearing it down here would delete it and post a fresh one.
        val playing = track("Song")
        val resume = track("Song (resumed)")
        every { playing.makeClone() } returns resume
        every { player.playingTrack } returns playing
        val intro = track("Intro")
        scheduler.queueIntro(intro, 0L, 5_000L, 50, null, "1_2_1")

        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.STOPPED)

        verify { player.startTrack(resume, false) }
        verify(exactly = 0) { MusicPlayerHelper.resetMessages(any()) }
    }

    @Test
    fun `the message goes when the music an intro interrupted finally ends`() {
        // The end of the whole sequence, which is where the message is
        // supposed to go — and where it stopped going once a voice-join intro
        // had wiped out the interaction teardown used to read the guild from.
        val playing = track("Song")
        val resume = track("Song (resumed)")
        every { playing.makeClone() } returns resume
        every { player.playingTrack } returns playing
        val intro = track("Intro")
        scheduler.queueIntro(intro, 0L, 5_000L, 50, null, "1_2_1")
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.STOPPED)

        scheduler.onTrackEnd(player, resume, AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { MusicPlayerHelper.resetMessages(1L) }
    }

    @Test
    fun `an intro that dies twice tears the message down exactly once`() {
        // Two deaths, two now-playing renders and two ends — and still one
        // teardown, not none and not one per attempt.
        val clone = retryAfterDeath(startMs = 0L, endMs = 8_000L)
        scheduler.onTrackException(player, clone, streamDied())

        scheduler.onTrackEnd(player, clone, AudioTrackEndReason.LOAD_FAILED)

        verify(exactly = 1) { MusicPlayerHelper.resetMessages(1L) }
    }

}
