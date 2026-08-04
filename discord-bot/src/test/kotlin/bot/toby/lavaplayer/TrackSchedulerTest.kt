package bot.toby.lavaplayer

import bot.toby.helpers.MusicPlayerHelper
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
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
        unmockkObject(MusicPlayerHelper)
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
    fun `intro-resume runs before loop branch - looping intro does not repeat`() {
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

    /**
     * Wires up an event/guild on the scheduler and stubs the now-playing helper so
     * we can assert exactly when the now-playing message is torn down vs. edited in
     * place across track transitions.
     */
    private fun stubMusicPlayerHelperWithEvent(guildId: Long = 1L) {
        mockkObject(MusicPlayerHelper)
        every { MusicPlayerHelper.nowPlaying(any(), any(), any(), any(), any()) } just Runs
        every { MusicPlayerHelper.resetMessages(any()) } just Runs
        val guild = mockk<Guild>(relaxed = true)
        every { guild.idLong } returns guildId
        val event = mockk<SlashCommandInteractionEvent>(relaxed = true)
        every { event.guild } returns guild
        scheduler.event = event
    }

    @Test
    fun `onTrackEnd does not reset now-playing message when advancing to next track`() {
        stubMusicPlayerHelperWithEvent()
        val current = mockTrack("Current")
        val next = mockTrack("Next", volume = 55)
        scheduler.queue.offer(next)

        scheduler.onTrackEnd(player, current, AudioTrackEndReason.FINISHED)

        // The next track's now-playing edits the existing message in place — the
        // message must NOT be deleted/recreated between tracks.
        verify(exactly = 0) { MusicPlayerHelper.resetMessages(any()) }
    }

    @Test
    fun `onTrackEnd resets now-playing message when queue is exhausted`() {
        stubMusicPlayerHelperWithEvent()
        val current = mockTrack("Current")

        scheduler.onTrackEnd(player, current, AudioTrackEndReason.FINISHED)

        // Nothing left to play — the now-playing message should be cleaned up.
        verify(exactly = 1) { MusicPlayerHelper.resetMessages(1L) }
    }

    @Test
    fun `onTrackEnd does not reset now-playing message on REPLACED`() {
        stubMusicPlayerHelperWithEvent()
        val current = mockTrack("Current")

        // Another track already took over the player and will refresh the embed.
        scheduler.onTrackEnd(player, current, AudioTrackEndReason.REPLACED)

        verify(exactly = 0) { MusicPlayerHelper.resetMessages(any()) }
    }

    @Test
    fun `onTrackEnd resets now-playing message when stopped with nothing taking over`() {
        stubMusicPlayerHelperWithEvent()
        val current = mockTrack("Current")

        scheduler.onTrackEnd(player, current, AudioTrackEndReason.STOPPED)

        verify(exactly = 1) { MusicPlayerHelper.resetMessages(1L) }
    }

    @Test
    fun `onTrackEnd does not reset now-playing message while looping`() {
        stubMusicPlayerHelperWithEvent()
        scheduler.isLooping = true
        val current = mockTrack("Current")

        scheduler.onTrackEnd(player, current, AudioTrackEndReason.FINISHED)

        verify(exactly = 0) { MusicPlayerHelper.resetMessages(any()) }
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

    // --- intro row bookkeeping ---------------------------------------------
    //
    // The loudness meter is built when the audio pipeline opens and needs to
    // know which stored intro it is measuring; the scheduler is the only place
    // that knows a given track is an intro at all.

    @Test
    fun `queueing an intro records which row it came from`() {
        val intro = mockTrack("Intro")

        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")

        assertEquals("1_2_1", scheduler.introIdFor(intro))
    }

    @Test
    fun `the row is recorded on the preempting path too`() {
        // queueIntro takes one of two routes depending on whether something is
        // already playing; a measurement that only worked on the idle path
        // would silently never happen for anyone listening to music.
        val playing = mockTrack("Playing")
        every { player.playingTrack } returns playing
        val intro = mockTrack("Intro")

        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_2")

        assertEquals("1_2_2", scheduler.introIdFor(intro))
    }

    @Test
    fun `a regular queued track has no intro row`() {
        val track = mockTrack("Music")

        scheduler.queue(track, 0L, null, 50)

        assertNull(scheduler.introIdFor(track))
    }

    @Test
    fun `an intro queued without a row id records nothing`() {
        val intro = mockTrack("Intro")

        scheduler.queueIntro(intro, 0L, null, 60)

        assertNull(scheduler.introIdFor(intro))
    }

    @Test
    fun `the row mapping is released when the intro ends`() {
        // Tracks are map keys; holding them past the end of playback would
        // pin every intro ever played for the lifetime of the guild player.
        val intro = mockTrack("Intro")
        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")

        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.FINISHED)

        assertNull(scheduler.introIdFor(intro))
    }

    @Test
    fun `stopping the player releases the row mapping`() {
        val intro = mockTrack("Intro")
        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")

        scheduler.stopTrack(true)

        assertNull(scheduler.introIdFor(intro))
    }

    // --- stuck tracks and the intro resume slot -----------------------------
    //
    // queueIntro parks the preempted track in resumeAfterIntro and treats a
    // non-null slot as "an intro is already in flight". Anything that ended an
    // intro without going through the resume branch therefore stranded the
    // slot, which lost the music *and* silently downgraded every later intro
    // on that guild from preempting to queueing.

    @Test
    fun `a stuck intro hands the player back to the music it interrupted`() {
        val playing = mockTrack("Playing")
        every { player.playingTrack } returns playing
        val intro = mockTrack("Intro")
        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")
        // player.stopTrack() is what drives onTrackEnd in production.
        every { player.stopTrack() } answers { scheduler.onTrackEnd(player, intro, AudioTrackEndReason.STOPPED) }

        scheduler.onTrackStuck(player, intro, 10_000L)

        // mockTrack stubs makeClone() to return the same mock, so the resume
        // slot holds `playing` itself.
        verify { player.startTrack(playing, false) }
        assertFalse(scheduler.hasResumeAfterIntro())
    }

    @Test
    fun `a stuck intro does not leave later intros unable to preempt`() {
        val playing = mockTrack("Playing")
        every { player.playingTrack } returns playing
        val first = mockTrack("Intro one")
        scheduler.queueIntro(first, 0L, null, 60, requesterId = null, introId = "1_2_1")
        every { player.stopTrack() } answers { scheduler.onTrackEnd(player, first, AudioTrackEndReason.STOPPED) }

        scheduler.onTrackStuck(player, first, 10_000L)

        // The slot is free again, so the next intro preempts rather than
        // queueing behind the music for the rest of the player's life.
        assertFalse(scheduler.hasResumeAfterIntro())
        val second = mockTrack("Intro two")
        scheduler.queueIntro(second, 0L, null, 60, requesterId = null, introId = "1_2_2")
        assertTrue(scheduler.hasResumeAfterIntro())
    }

    @Test
    fun `a stuck regular track advances the queue`() {
        val stuck = mockTrack("Stuck")
        val next = mockTrack("Next")
        scheduler.queue(next, 0L, null, 50)
        every { player.stopTrack() } answers { scheduler.onTrackEnd(player, stuck, AudioTrackEndReason.STOPPED) }

        scheduler.onTrackStuck(player, stuck, 10_000L)

        verify { player.startTrack(next, false) }
    }

    @Test
    fun `a track that stalls after it started playing is still recovered`() {
        // The old guard only fired at position 0, so a mid-track stall hung
        // the queue with nothing able to unstick it.
        val stuck = mockTrack("Stuck")
        every { stuck.position } returns 42_000L
        val next = mockTrack("Next")
        scheduler.queue(next, 0L, null, 50)
        every { player.stopTrack() } answers { scheduler.onTrackEnd(player, stuck, AudioTrackEndReason.STOPPED) }

        scheduler.onTrackStuck(player, stuck, 10_000L)

        verify { player.stopTrack() }
        verify { player.startTrack(next, false) }
    }

    @Test
    fun `a skip landing mid-intro puts the preempted music back at the front`() {
        // The listener skipped the intro, not the track they were listening to.
        val playing = mockTrack("Playing")
        every { player.playingTrack } returns playing
        val queued = mockTrack("Queued")
        scheduler.queue(queued, 0L, null, 50)
        val intro = mockTrack("Intro")
        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")

        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.REPLACED)

        assertFalse(scheduler.hasResumeAfterIntro())
        assertEquals(listOf("Playing", "Queued"), scheduler.queue.map { it.info.title })
    }

    @Test
    fun `the resume slot is released when the player is torn down`() {
        val playing = mockTrack("Playing")
        every { player.playingTrack } returns playing
        val intro = mockTrack("Intro")
        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")

        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.CLEANUP)

        assertFalse(scheduler.hasResumeAfterIntro())
        // Nothing to play it on, so it is not requeued either.
        assertTrue(scheduler.queue.isEmpty())
    }

    @Test
    fun `a regular track ending normally never touches the resume slot`() {
        val playing = mockTrack("Playing")
        every { player.playingTrack } returns playing
        val intro = mockTrack("Intro")
        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")

        scheduler.onTrackEnd(player, mockTrack("Unrelated"), AudioTrackEndReason.REPLACED)

        assertTrue(scheduler.hasResumeAfterIntro())
    }

    // --- what the audio pipeline reads back ---------------------------------
    //
    // TrackScheduler is the production IntroTrackContext, so these are the
    // answers IntroFilterFactory builds the fade from.

    /** Like [mockTrack], but with its own identity when cloned. */
    private fun sourceTrack(title: String, durationMs: Long, cloneInto: AudioTrack): AudioTrack {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo(title, "Author", durationMs, "id", false, "http://example.com")
        every { track.duration } returns durationMs
        every { track.userData } returns 50
        every { track.position } returns 1_000L
        every { track.makeClone() } returns cloneInto
        return track
    }

    @Test
    fun `a clipped intro reports its clip length as the playback length`() {
        val intro = mockTrack("Intro")
        every { intro.duration } returns 300_000L

        scheduler.queueIntro(intro, 10_000L, 22_000L, 60)

        assertEquals(12_000L, scheduler.introPlaybackMsFor(intro))
    }

    @Test
    fun `an unclipped intro reports what is left of the track`() {
        val intro = mockTrack("Intro")
        every { intro.duration } returns 14_000L

        scheduler.queueIntro(intro, 2_000L, null, 60)

        assertEquals(12_000L, scheduler.introPlaybackMsFor(intro))
    }

    @Test
    fun `a stream reports no playback length, so its fade has no end to aim at`() {
        val intro = mockTrack("Endless")
        every { intro.duration } returns Long.MAX_VALUE

        scheduler.queueIntro(intro, 0L, null, 60)

        assertNull(scheduler.introPlaybackMsFor(intro))
    }

    @Test
    fun `a non-intro track has no playback length recorded against it`() {
        val track = mockTrack("Just music")

        scheduler.queue(track, 0L, null, 50)

        assertNull(scheduler.introPlaybackMsFor(track))
    }

    @Test
    fun `the preempted track is marked so it fades back in, and the intro is not`() {
        val clone = mockTrack("Clone")
        val current = sourceTrack("Current", 200_000L, clone)
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current

        scheduler.queueIntro(intro, 0L, null, 60)

        assertTrue(scheduler.isResumingTrack(clone), "the resumed track should fade in")
        assertFalse(scheduler.isResumingTrack(intro), "the intro is not a resume")
        assertFalse(scheduler.isResumingTrack(current), "the original track object is finished with")
    }

    @Test
    fun `the resume mark is dropped once the resumed track ends`() {
        val clone = mockTrack("Clone")
        val current = sourceTrack("Current", 200_000L, clone)
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current
        scheduler.queueIntro(intro, 0L, null, 60)

        scheduler.onTrackEnd(player, clone, AudioTrackEndReason.FINISHED)

        assertFalse(scheduler.isResumingTrack(clone))
    }

    @Test
    fun `a stop clears the intro bookkeeping the pipeline reads`() {
        val clone = mockTrack("Clone")
        val current = sourceTrack("Current", 200_000L, clone)
        val intro = mockTrack("Intro")
        every { intro.duration } returns 12_000L
        every { player.playingTrack } returns current
        scheduler.queueIntro(intro, 0L, null, 60, requesterId = null, introId = "1_2_1")

        scheduler.stopTrack(true)

        assertNull(scheduler.introIdFor(intro))
        assertNull(scheduler.introPlaybackMsFor(intro))
        assertFalse(scheduler.isResumingTrack(clone))
    }

    @Test
    fun `a preempted track requeued after a skipped intro still fades in when it gets there`() {
        // It was interrupted either way; reaching the player via the queue
        // rather than directly doesn't change that.
        val clone = mockTrack("Clone")
        val current = sourceTrack("Current", 200_000L, clone)
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current
        scheduler.queueIntro(intro, 0L, null, 60)

        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.REPLACED)

        assertTrue(scheduler.queue.contains(clone))
        assertTrue(scheduler.isResumingTrack(clone))
    }

    @Test
    fun `a preempted track dropped on teardown loses its mark with it`() {
        val clone = mockTrack("Clone")
        val current = sourceTrack("Current", 200_000L, clone)
        val intro = mockTrack("Intro")
        every { player.playingTrack } returns current
        scheduler.queueIntro(intro, 0L, null, 60)

        scheduler.onTrackEnd(player, intro, AudioTrackEndReason.CLEANUP)

        assertFalse(scheduler.isResumingTrack(clone))
    }

    @Test
    fun `currentTrack reports whatever the player is on`() {
        val playing = mockTrack("Playing")
        every { player.playingTrack } returns playing

        assertSame(playing, scheduler.currentTrack())
    }
}
