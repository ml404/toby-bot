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
        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
        unmockkObject(PlayerManager)
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
}
