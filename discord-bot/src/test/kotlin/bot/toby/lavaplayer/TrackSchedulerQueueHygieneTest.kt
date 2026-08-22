package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import com.sedmelluq.discord.lavaplayer.track.TrackMarkerHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The per-track maps are populated when a track is *queued* but were only ever
 * cleaned when one *ended*, so every bare `queue.clear()` — on a leave, a stop,
 * an empty channel — orphaned an entry for each track that never got to play.
 * They are keyed by `AudioTrack`, so each orphan pinned a whole track object
 * for as long as the process lived.
 */
class TrackSchedulerQueueHygieneTest {

    private val player: AudioPlayer = mockk(relaxed = true)
    private lateinit var scheduler: TrackScheduler

    @BeforeEach
    fun setUp() {
        every { player.playingTrack } returns mockk(relaxed = true)
        every { player.startTrack(any(), any()) } returns false
        scheduler = TrackScheduler(player, guildId = 1L, deleteDelay = 5, outcomeReporter = mockk(relaxed = true))
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun track(title: String = "Queued"): AudioTrack = mockk(relaxed = true) {
        every { info } returns AudioTrackInfo(title, "Author", 60_000L, "id", false, "http://x")
        every { userData } returns 50
        every { makeClone() } returns this@mockk
    }

    @Test
    fun `clearing the queue forgets what was keyed to it`() {
        val queued = track()
        scheduler.queue(queued, startPosition = 0L, endPosition = 5_000L, volume = 50, requesterId = 42L)
        assertEquals(42L, scheduler.getRequesterId(queued))

        scheduler.clearQueue()

        assertNull(scheduler.getRequesterId(queued))
        assertEquals(0, scheduler.queue.size)
    }

    @Test
    fun `an intro dropped from the queue takes its intro bookkeeping with it`() {
        // Nothing playing and the player declining to start it, which is the
        // one route by which an intro ends up sitting in the queue rather than
        // going straight onto the player.
        every { player.playingTrack } returns null
        val intro = track("Intro")
        scheduler.queueIntro(intro, 0L, null, 50, requesterId = 7L, introId = "1_2_1")
        assertEquals("1_2_1", scheduler.introIdFor(intro))

        scheduler.clearQueue()

        assertNull(scheduler.introIdFor(intro))
        assertNull(scheduler.introPlaybackMsFor(intro))
        assertEquals(false, scheduler.isIntroTrack(intro))
    }

    @Test
    fun `stopping forgets the clip bounds and requester it used to leave behind`() {
        val queued = track()
        scheduler.queue(queued, startPosition = 0L, endPosition = 5_000L, volume = 50, requesterId = 42L)

        scheduler.stopTrack(isStoppable = true)

        assertNull(scheduler.getRequesterId(queued))
    }

    @Test
    fun `removing one item leaves the others alone`() {
        val first = track("First")
        val second = track("Second")
        scheduler.queue(first, 0L, null, 50, requesterId = 1L)
        scheduler.queue(second, 0L, null, 50, requesterId = 2L)

        scheduler.removeQueueItem(0)

        assertNull(scheduler.getRequesterId(first))
        assertEquals(2L, scheduler.getRequesterId(second))
    }

    @Test
    fun `reordering keeps every record, since nothing is dropped`() {
        val first = track("First")
        val second = track("Second")
        scheduler.queue(first, 0L, null, 50, requesterId = 1L)
        scheduler.queue(second, 0L, null, 50, requesterId = 2L)

        scheduler.moveQueueItem(0, 1)

        assertEquals(1L, scheduler.getRequesterId(first))
        assertEquals(2L, scheduler.getRequesterId(second))
    }

    @Test
    fun `a clipped track stops itself at its end marker`() {
        // Every clipped intro ends this way, and nothing exercised it. The
        // stop goes through the player rather than straight to nextTrack so
        // that onTrackEnd fires — that is the handler which tears down the
        // now-playing embed, restores the volume and puts back a preempted
        // track.
        val marker = slot<TrackMarker>()
        val clipped = track("Clipped")
        every { clipped.setMarker(capture(marker)) } returns Unit

        scheduler.queue(clipped, startPosition = 1_000L, endPosition = 5_000L, volume = 50, requesterId = null)

        assertEquals(5_000L, marker.captured.timecode)
        marker.captured.handler.handle(TrackMarkerHandler.MarkerState.REACHED)
        verify(exactly = 1) { player.stopTrack() }
    }

    @Test
    fun `a marker that fires for any other reason leaves the track alone`() {
        // BYPASSED, LATE, ENDED and STOPPED all arrive here too; only REACHED
        // means the clip boundary was actually hit.
        val marker = slot<TrackMarker>()
        val clipped = track("Clipped")
        every { clipped.setMarker(capture(marker)) } returns Unit
        scheduler.queue(clipped, startPosition = 0L, endPosition = 5_000L, volume = 50, requesterId = null)

        marker.captured.handler.handle(TrackMarkerHandler.MarkerState.BYPASSED)
        marker.captured.handler.handle(TrackMarkerHandler.MarkerState.LATE)
        marker.captured.handler.handle(TrackMarkerHandler.MarkerState.ENDED)

        verify(exactly = 0) { player.stopTrack() }
    }

    @Test
    fun `an unclipped track gets no marker at all`() {
        val whole = track("Whole")

        scheduler.queue(whole, startPosition = 0L, endPosition = null, volume = 50, requesterId = null)

        verify(exactly = 0) { whole.setMarker(any()) }
    }

    @Test
    fun `an end that is not after the start is not a clip`() {
        // Nonsense bounds would otherwise arm a marker that fires immediately.
        val backwards = track("Backwards")

        scheduler.queue(backwards, startPosition = 5_000L, endPosition = 1_000L, volume = 50, requesterId = null)

        verify(exactly = 0) { backwards.setMarker(any()) }
    }

    @Test
    fun `an intro that cannot be queued is not silently dropped`() {
        // The queue is bounded at 100 and offer()'s return used to be
        // discarded, so past that point an intro vanished without a word.
        every { player.playingTrack } returns null
        repeat(100) { scheduler.queue(track("Filler $it"), 0L, null, 50, null) }
        assertEquals(100, scheduler.queue.size)

        val unlucky = track("Unlucky")
        scheduler.queueIntro(unlucky, 0L, null, 50, requesterId = 7L, introId = "1_2_1")

        // Not queued, and nothing kept keyed to it.
        assertEquals(100, scheduler.queue.size)
        assertNull(scheduler.introIdFor(unlucky))
        assertNull(scheduler.getRequesterId(unlucky))
    }

    @Test
    fun `clearing an already empty queue is quiet`() {
        scheduler.clearQueue()

        assertEquals(0, scheduler.queue.size)
    }
}
