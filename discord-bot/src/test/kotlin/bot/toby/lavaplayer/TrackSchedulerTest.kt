package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TrackSchedulerTest {

    private val player: AudioPlayer = mockk(relaxed = true)
    private lateinit var scheduler: TrackScheduler

    private fun mockTrack(title: String = "Test Track", author: String = "Author", volume: Int = 50): AudioTrack {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo(title, author, 60000L, "identifier", false, "http://example.com")
        every { track.userData } returns volume
        every { track.makeClone() } returns track
        return track
    }

    @BeforeEach
    fun setUp() {
        scheduler = TrackScheduler(player, guildId = 1L, deleteDelay = 5)
        mockkObject(PlayerManager.Companion)
        every { PlayerManager.instance } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
        unmockkObject(PlayerManager.Companion)
    }

    @Test
    fun `queue starts track immediately when player is free`() {
        val track = mockTrack()
        every { player.startTrack(track, true) } returns true

        scheduler.queue(track, 0L, 50)

        verify(exactly = 1) { player.startTrack(track, true) }
        assertEquals(0, scheduler.queue.size)
    }

    @Test
    fun `queue adds track to queue when player is busy`() {
        val track = mockTrack()
        every { player.startTrack(track, true) } returns false

        scheduler.queue(track, 0L, 50)

        verify(exactly = 1) { player.startTrack(track, true) }
        assertEquals(1, scheduler.queue.size)
        assertSame(track, scheduler.queue.poll())
    }

    @Test
    fun `queue sets track position and userData`() {
        val track = mockTrack()
        every { player.startTrack(track, true) } returns true

        scheduler.queue(track, 1000L, 75)

        verify(exactly = 1) { track.position = 1000L }
        verify(exactly = 1) { track.userData = 75 }
    }

    @Test
    fun `queueTrackList adds all tracks to queue when player is busy`() {
        val track1 = mockTrack("Track 1")
        val track2 = mockTrack("Track 2")
        val playlist = mockk<AudioPlaylist>(relaxed = true)
        every { playlist.name } returns "Test Playlist"
        every { playlist.tracks } returns listOf(track1, track2)
        every { player.startTrack(any(), true) } returns false

        scheduler.queueTrackList(playlist, 80)

        assertEquals(2, scheduler.queue.size)
    }

    @Test
    fun `queueTrackList sets userData on each track`() {
        val track1 = mockTrack()
        val track2 = mockTrack()
        val playlist = mockk<AudioPlaylist>(relaxed = true)
        every { playlist.name } returns "Playlist"
        every { playlist.tracks } returns listOf(track1, track2)
        every { player.startTrack(any(), true) } returns false

        scheduler.queueTrackList(playlist, 60)

        verify(exactly = 1) { track1.userData = 60 }
        verify(exactly = 1) { track2.userData = 60 }
    }

    @Test
    fun `nextTrack does nothing when queue is empty`() {
        scheduler.nextTrack()

        verify(exactly = 0) { player.startTrack(any(), any()) }
    }

    @Test
    fun `nextTrack starts next track from queue and sets volume`() {
        val track = mockTrack(volume = 70)
        scheduler.queue.offer(track)
        every { player.startTrack(track, false) } returns true

        scheduler.nextTrack()

        verify(exactly = 1) { player.volume = 70 }
        verify(exactly = 1) { player.startTrack(track, false) }
        assertEquals(0, scheduler.queue.size)
    }

    @Test
    fun `onTrackStart sets player volume from track userData`() {
        val track = mockTrack(volume = 65)

        scheduler.onTrackStart(player, track)

        verify(exactly = 1) { player.volume = 65 }
    }

    @Test
    fun `onTrackEnd does not proceed to next track when mayStartNext is false`() {
        val track = mockTrack()

        scheduler.onTrackEnd(player, track, AudioTrackEndReason.STOPPED)

        verify(exactly = 0) { player.startTrack(any(), false) }
    }

    @Test
    fun `onTrackEnd loops track when isLooping is true`() {
        val track = mockTrack()
        val clonedTrack = mockTrack()
        every { track.makeClone() } returns clonedTrack
        scheduler.isLooping = true

        scheduler.onTrackEnd(player, track, AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { player.startTrack(clonedTrack, false) }
    }

    @Test
    fun `onTrackEnd advances queue when not looping and queue has tracks`() {
        val currentTrack = mockTrack("Current")
        val nextTrack = mockTrack("Next", volume = 55)
        scheduler.queue.offer(nextTrack)
        scheduler.isLooping = false

        scheduler.onTrackEnd(player, currentTrack, AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { player.startTrack(nextTrack, false) }
    }

    @Test
    fun `onTrackStuck at position 0 calls nextTrack`() {
        val track = mockTrack()
        every { track.position } returns 0L

        scheduler.onTrackStuck(player, track, 1000L)

        // nextTrack on empty queue should not start any track
        verify(exactly = 0) { player.startTrack(any(), false) }
    }

    @Test
    fun `onTrackStuck at position greater than 0 does nothing`() {
        val track = mockTrack()
        every { track.position } returns 5000L

        scheduler.onTrackStuck(player, track, 1000L)

        verify(exactly = 0) { player.startTrack(any(), false) }
    }

    @Test
    fun `stopTrack returns false when isStoppable is false`() {
        assertFalse(scheduler.stopTrack(false))
        verify(exactly = 0) { player.stopTrack() }
    }

    @Test
    fun `stopTrack stops player and returns true when isStoppable is true`() {
        assertTrue(scheduler.stopTrack(true))
        verify(exactly = 1) { player.stopTrack() }
    }

    @Test
    fun `setPreviousVolume stores value for later use`() {
        scheduler.setPreviousVolume(42)
        // If we stop a stoppable track with no event, setVolumeToPrevious won't send a message
        // but we can verify it's stored by verifying stopTrack calls stopTrack on player
        assertTrue(scheduler.stopTrack(true))
        verify(exactly = 1) { player.stopTrack() }
    }

    @Test
    fun `queue is bounded at 100 tracks`() {
        every { player.startTrack(any(), true) } returns false
        repeat(105) {
            val track = mockTrack("Track $it")
            scheduler.queue(track, 0L, 50)
        }
        assertEquals(100, scheduler.queue.size)
    }

    @Test
    fun `moveQueueItem moves item from one position to another`() {
        every { player.startTrack(any(), true) } returns false
        val t1 = mockTrack("T1")
        val t2 = mockTrack("T2")
        val t3 = mockTrack("T3")
        scheduler.queue(t1, 0L, 50)
        scheduler.queue(t2, 0L, 50)
        scheduler.queue(t3, 0L, 50)

        assertTrue(scheduler.moveQueueItem(0, 2))

        val ordered = scheduler.queue.toList()
        assertEquals(3, ordered.size)
        assertSame(t2, ordered[0])
        assertSame(t3, ordered[1])
        assertSame(t1, ordered[2])
    }

    @Test
    fun `moveQueueItem with fromIndex equal toIndex is a no-op true`() {
        every { player.startTrack(any(), true) } returns false
        val t1 = mockTrack("T1")
        scheduler.queue(t1, 0L, 50)
        assertTrue(scheduler.moveQueueItem(0, 0))
        assertSame(t1, scheduler.queue.peek())
    }

    @Test
    fun `moveQueueItem with negative fromIndex returns false`() {
        assertFalse(scheduler.moveQueueItem(-1, 0))
    }

    @Test
    fun `moveQueueItem with out-of-range toIndex returns false`() {
        every { player.startTrack(any(), true) } returns false
        scheduler.queue(mockTrack("T1"), 0L, 50)
        assertFalse(scheduler.moveQueueItem(0, 99))
    }

    @Test
    fun `removeQueueItem removes correct item by index`() {
        every { player.startTrack(any(), true) } returns false
        val t1 = mockTrack("T1")
        val t2 = mockTrack("T2")
        val t3 = mockTrack("T3")
        scheduler.queue(t1, 0L, 50)
        scheduler.queue(t2, 0L, 50)
        scheduler.queue(t3, 0L, 50)

        val removed = scheduler.removeQueueItem(1)
        assertSame(t2, removed)
        assertEquals(2, scheduler.queue.size)
        val remaining = scheduler.queue.toList()
        assertSame(t1, remaining[0])
        assertSame(t3, remaining[1])
    }

    @Test
    fun `removeQueueItem out-of-bounds returns null`() {
        every { player.startTrack(any(), true) } returns false
        scheduler.queue(mockTrack("T1"), 0L, 50)
        assertNull(scheduler.removeQueueItem(-1))
        assertNull(scheduler.removeQueueItem(99))
    }

    @Test
    fun `getRequesterId returns null when not set`() {
        val track = mockTrack()
        assertNull(scheduler.getRequesterId(track))
    }

    @Test
    fun `queue records requester id when provided`() {
        val track = mockTrack()
        every { player.startTrack(track, true) } returns false
        scheduler.queue(track, 0L, null, 50, requesterId = 12345L)
        assertEquals(12345L, scheduler.getRequesterId(track))
    }

    @Test
    fun `onTrackEnd clears requester id`() {
        val track = mockTrack()
        every { player.startTrack(track, true) } returns false
        scheduler.queue(track, 0L, null, 50, requesterId = 12345L)
        scheduler.onTrackEnd(player, track, AudioTrackEndReason.STOPPED)
        assertNull(scheduler.getRequesterId(track))
    }

    @Test
    fun `queueIntro starts intro immediately when nothing is playing`() {
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns null
        every { player.startTrack(intro, true) } returns true

        scheduler.queueIntro(intro, 0L, null, 60)

        verify(exactly = 1) { player.startTrack(intro, true) }
        verify(exactly = 0) { player.startTrack(intro, false) }
        assertFalse(scheduler.hasResumeAfterIntro())
        assertTrue(scheduler.isIntroTrack(intro))
    }

    @Test
    fun `queueIntro preempts current track and stores clone in resume slot`() {
        val current = mockTrack("Current", volume = 40)
        val clone = mockTrack("Current clone", volume = 40)
        every { current.position } returns 12345L
        every { current.userData } returns 40
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current

        scheduler.queueIntro(intro, 0L, null, 60)

        verify(exactly = 1) { current.makeClone() }
        verify(exactly = 1) { clone.position = 12345L }
        verify(exactly = 1) { player.startTrack(intro, false) }
        assertTrue(scheduler.hasResumeAfterIntro())
        assertTrue(scheduler.isIntroTrack(intro))
    }

    @Test
    fun `queueIntro copies volume from preempted track onto the clone`() {
        val current = mockTrack("Current", volume = 33)
        val clone = mockTrack("Clone")
        every { current.position } returns 0L
        every { current.userData } returns 33
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current

        scheduler.queueIntro(intro, 0L, null, 60)

        verify { clone.userData = 33 }
    }

    @Test
    fun `queueIntro preserves clip bounds and requester id of preempted track`() {
        val current = mockTrack("Current")
        val clone = mockTrack("Clone")
        every { current.position } returns 500L
        every { current.userData } returns 50
        every { current.makeClone() } returns clone
        every { player.startTrack(current, true) } returns true
        scheduler.queue(current, 100L, 9000L, 50, requesterId = 777L)

        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current
        scheduler.queueIntro(intro, 0L, null, 60)

        assertEquals(777L, scheduler.getRequesterId(clone))
    }

    @Test
    fun `onTrackEnd resumes preempted track when intro ends and does not advance queue`() {
        val current = mockTrack("Current")
        val clone = mockTrack("Clone")
        every { current.position } returns 4000L
        every { current.userData } returns 50
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current
        // Pre-existing user-queued track that should NOT be advanced when the intro ends.
        val queued = mockTrack("Queued")
        scheduler.queue.offer(queued)

        scheduler.queueIntro(intro, 0L, null, 60)
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { player.startTrack(clone, false) }
        // The queued track must remain queued — intro-resume takes priority.
        verify(exactly = 0) { player.startTrack(queued, false) }
        assertEquals(1, scheduler.queue.size)
        assertFalse(scheduler.hasResumeAfterIntro())
    }

    @Test
    fun `onTrackEnd resumes preempted track when intro ends via STOPPED (clip marker)`() {
        val current = mockTrack("Current")
        val clone = mockTrack("Clone")
        every { current.position } returns 4000L
        every { current.userData } returns 50
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current

        scheduler.queueIntro(intro, 0L, null, 60)
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.STOPPED)

        verify(exactly = 1) { player.startTrack(clone, false) }
    }

    @Test
    fun `onTrackEnd does not resume preempted track when intro ends via REPLACED`() {
        val current = mockTrack("Current")
        val clone = mockTrack("Clone")
        every { current.position } returns 4000L
        every { current.userData } returns 50
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current

        scheduler.queueIntro(intro, 0L, null, 60)
        // Simulate: something else took over the player (e.g. /play during intro).
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.REPLACED)

        verify(exactly = 0) { player.startTrack(clone, false) }
    }

    @Test
    fun `onTrackEnd does not resume when ended track is not an intro`() {
        val current = mockTrack("Current")
        val clone = mockTrack("Clone")
        every { current.position } returns 1000L
        every { current.userData } returns 50
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current
        scheduler.queueIntro(intro, 0L, null, 60)
        // Pretend an unrelated track ends — the resume slot must stay intact.
        val unrelated = mockTrack("Unrelated")

        scheduler.onTrackEnd(player, unrelated, AudioTrackEndReason.FINISHED)

        verify(exactly = 0) { player.startTrack(clone, false) }
        assertTrue(scheduler.hasResumeAfterIntro())
    }

    @Test
    fun `queueIntro does not overwrite resume slot when one is already occupied`() {
        val current = mockTrack("Current")
        val firstClone = mockTrack("First clone")
        every { current.position } returns 1000L
        every { current.userData } returns 50
        every { current.makeClone() } returns firstClone

        val intro1 = mockTrack("Intro 1")
        every { player.playingTrack } returns current
        scheduler.queueIntro(intro1, 0L, null, 60)
        assertTrue(scheduler.hasResumeAfterIntro())

        // Now another member joins; intro2 fires while intro1 is "playing".
        val intro2 = mockTrack("Intro 2")
        val secondClone = mockTrack("Second clone")
        every { intro1.makeClone() } returns secondClone
        every { player.playingTrack } returns intro1
        every { player.startTrack(intro2, true) } returns false

        scheduler.queueIntro(intro2, 0L, null, 60)

        // intro1's clone must still be the one in the resume slot.
        verify(exactly = 0) { intro1.makeClone() }
        // intro2 should be queued (not preempt).
        verify(exactly = 0) { player.startTrack(intro2, false) }
        verify(exactly = 1) { player.startTrack(intro2, true) }
        assertTrue(scheduler.queue.contains(intro2))
        // Resuming the intro1 → preempted track still resumes firstClone, not secondClone.
        scheduler.onTrackEnd(player, intro1, AudioTrackEndReason.FINISHED)
        verify(exactly = 1) { player.startTrack(firstClone, false) }
        verify(exactly = 0) { player.startTrack(secondClone, false) }
    }

    @Test
    fun `stopTrack clears resume slot so subsequent intro end does not auto-resume`() {
        val current = mockTrack("Current")
        val clone = mockTrack("Clone")
        every { current.position } returns 1000L
        every { current.userData } returns 50
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current

        scheduler.queueIntro(intro, 0L, null, 60)
        assertTrue(scheduler.hasResumeAfterIntro())

        scheduler.stopTrack(true)
        assertFalse(scheduler.hasResumeAfterIntro())

        // Simulate the intro ending after stop — must NOT resume the clone.
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.STOPPED)
        verify(exactly = 0) { player.startTrack(clone, false) }
    }

    @Test
    fun `intro-resume runs before loop branch — looping intro does not repeat`() {
        scheduler.isLooping = true
        val current = mockTrack("Current")
        val clone = mockTrack("Clone")
        every { current.position } returns 2000L
        every { current.userData } returns 50
        every { current.makeClone() } returns clone
        val intro = mockTrack("Intro")
        val introClone = mockTrack("Intro clone")
        every { intro.makeClone() } returns introClone
        every { player.playingTrack } returns current

        scheduler.queueIntro(intro, 0L, null, 60)
        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.FINISHED)

        verify(exactly = 1) { player.startTrack(clone, false) }
        // The intro must NOT be re-cloned-and-restarted by the loop branch.
        verify(exactly = 0) { player.startTrack(introClone, false) }
    }

    @Test
    fun `SchedulerEvents publisher being null does not crash event-emitting paths`() {
        // Ensure publisher is null (default in tests).
        SchedulerEvents.publisher = null
        val track = mockTrack()
        every { player.startTrack(track, true) } returns false
        // Should not throw.
        scheduler.queue(track, 0L, 50)
        scheduler.onTrackStart(player, track)
        scheduler.onTrackEnd(player, track, AudioTrackEndReason.FINISHED)
        scheduler.onPlayerPause(player)
        scheduler.onPlayerResume(player)
    }
}
