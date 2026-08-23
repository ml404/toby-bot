package bot.toby.managers

import bot.toby.lavaplayer.TrackScheduler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import common.testing.DeterministicScheduler
import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertTrue
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

class NowPlayingManagerTest {

    private lateinit var nowPlayingManager: NowPlayingManager
    private lateinit var scheduler: DeterministicScheduler
    private lateinit var mockMessage1: Message
    private lateinit var mockMessage2: Message

    @BeforeEach
    fun setUp() {
        scheduler = DeterministicScheduler()
        nowPlayingManager = NowPlayingManager(scheduler = scheduler)
        mockMessage1 = mockk(relaxed = true)
        mockMessage2 = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        nowPlayingManager.clear()
    }

    @Test
    fun `test setting and getting messages`() {
        val guildId = 1L

        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1)
        assertEquals(mockMessage1, nowPlayingManager.getLastNowPlayingMessage(guildId))

        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage2)
        assertEquals(mockMessage2, nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `test resetting existing message`() {
        val guildId = 1L
        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1)

        // Create a CompletableFuture to simulate success
        val future = CompletableFuture<Void>()
        every { mockMessage1.delete().submit() } returns future

        nowPlayingManager.resetNowPlayingMessage(guildId)

        // Complete the future to simulate the successful deletion
        future.complete(null)

        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Verify that submit() was called
        verify { mockMessage1.delete().submit() }
    }

    @Test
    fun `test resetting non-existent message`() {
        val guildId = 1L
        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1)

        // Create a CompletableFuture to simulate success
        val future = CompletableFuture<Void>()
        every { mockMessage1.delete().submit() } returns future

        // Reset the message once
        nowPlayingManager.resetNowPlayingMessage(guildId)

        // Ensure the future is completed before proceeding
        future.complete(null)

        // Call reset again which should be a no-op since the message is already deleted
        nowPlayingManager.resetNowPlayingMessage(guildId)

        // Verify that the message was removed from the map
        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Verify that submit() was called only once
        verify(exactly = 1) { mockMessage1.delete().submit() }
    }

    @Test
    fun `test clearing messages`() {
        val guildId1 = 1L
        val guildId2 = 2L
        nowPlayingManager.setNowPlayingMessage(guildId1, mockMessage1)
        nowPlayingManager.setNowPlayingMessage(guildId2, mockMessage2)

        nowPlayingManager.clear()

        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId1))
        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId2))
    }

    @Test
    fun `test concurrent access`() {
        val guildId1 = 1L
        val guildId2 = 2L

        nowPlayingManager.setNowPlayingMessage(guildId1, mockMessage1)
        nowPlayingManager.setNowPlayingMessage(guildId2, mockMessage2)

        val threads = List(10) {
            thread {
                nowPlayingManager.getLastNowPlayingMessage(guildId1)
                nowPlayingManager.setNowPlayingMessage(guildId2, mockMessage2)
            }
        }
        threads.forEach { it.join() }

        // Verify results
        assertEquals(mockMessage1, nowPlayingManager.getLastNowPlayingMessage(guildId1))
        assertEquals(mockMessage2, nowPlayingManager.getLastNowPlayingMessage(guildId2))
    }

    @Test
    fun `test sending, resetting, and sending another message`() {
        val guildId = 1L

        // Create mock messages
        val message1 = mockk<Message>(relaxed = true)
        val message2 = mockk<Message>(relaxed = true)

        // Mock behavior for message1 and message2
        every { message1.idLong } returns 1L
        every { message2.idLong } returns 2L

        // Create a CompletableFuture to simulate success
        val future1 = CompletableFuture<Void>()
        val future2 = CompletableFuture<Void>()
        every { message1.delete().submit() } returns future1
        every { message2.delete().submit() } returns future2

        // Set the first message
        nowPlayingManager.setNowPlayingMessage(guildId, message1)

        // Verify that the first message is set
        assertEquals(message1, nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Clear the messages
        nowPlayingManager.resetNowPlayingMessage(guildId)

        // Complete the future to simulate the successful deletion
        future1.complete(null)

        // Verify that the message is cleared
        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Set the second message
        nowPlayingManager.setNowPlayingMessage(guildId, message2)

        // Complete the future to simulate the successful deletion
        future2.complete(null)

        // Verify that the second message is correctly set
        assertEquals(message2, nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `test scheduleNowPlayingUpdate schedules update correctly`() {
        // Given
        val mockAudioPlayer = mockk<AudioPlayer>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        every { mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue() } just Runs

        val guildId = 1L
        val delay = 0L
        val period = 1L

        every { mockAudioPlayer.volume } returns 50
        every { mockAudioPlayer.isPaused } returns false
        every { mockAudioTrack.info } returns AudioTrackInfo("Test Title", "Test Author", 3000L, "", false, "http://example.com")

        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1)

        // When
        nowPlayingManager.scheduleNowPlayingUpdate(guildId, mockAudioTrack, mockAudioPlayer, delay, period)

        // Advance the deterministic scheduler — fires the captured task once.
        scheduler.runPending()

        // Then
        verify(exactly = 1) {
            mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
    }

    @Test
    fun `test concurrent scheduling and updating for multiple guilds`() {
        // Given
        val mockAudioPlayer1 = mockk<AudioPlayer>(relaxed = true)
        val mockAudioPlayer2 = mockk<AudioPlayer>(relaxed = true)
        val mockAudioTrack1 = mockk<AudioTrack>(relaxed = true)
        val mockAudioTrack2 = mockk<AudioTrack>(relaxed = true)
        every { mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue() } just Runs
        every { mockMessage2.editMessageEmbeds(any<MessageEmbed>()).queue() } just Runs

        val guildId1 = 1L
        val guildId2 = 2L
        val delay = 0L
        val period = 1L

        every { mockAudioPlayer1.volume } returns 50
        every { mockAudioPlayer1.isPaused } returns false
        every { mockAudioPlayer2.volume } returns 50
        every { mockAudioPlayer2.isPaused } returns false
        every { mockAudioTrack1.info } returns AudioTrackInfo("Test Title 1", "Test Author 1", 3000L, "", false, "http://example.com")
        every { mockAudioTrack2.info } returns AudioTrackInfo("Test Title 2", "Test Author 2", 3000L, "", false, "http://example.com")

        nowPlayingManager.setNowPlayingMessage(guildId1, mockMessage1)
        nowPlayingManager.setNowPlayingMessage(guildId2, mockMessage2)

        // When
        nowPlayingManager.scheduleNowPlayingUpdate(guildId1, mockAudioTrack1, mockAudioPlayer1, delay, period)
        nowPlayingManager.scheduleNowPlayingUpdate(guildId2, mockAudioTrack2, mockAudioPlayer2, delay, period)

        scheduler.runPending()

        // Then
        verify(exactly = 1) {
            mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
        verify(exactly = 1) {
            mockMessage2.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
    }

    @Test
    fun `test cancelScheduledTask cancels task`() {
        // Given
        val guildId = 1L
        val mockAudioPlayer = mockk<AudioPlayer>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        every { mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue() } just Runs

        val delay = 0L
        val period = 1L

        every { mockAudioPlayer.volume } returns 50
        every { mockAudioPlayer.isPaused } returns false
        every { mockAudioTrack.info } returns AudioTrackInfo("Test Title", "Test Author", 3000L, "", false, "http://example.com")

        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1)
        nowPlayingManager.scheduleNowPlayingUpdate(guildId, mockAudioTrack, mockAudioPlayer, delay, period)

        // Run the scheduled task once before cancelling.
        scheduler.runPending()

        // When
        nowPlayingManager.cancelScheduledTask(guildId)

        // Any pending tasks left after cancellation must not fire.
        scheduler.runPending()

        // Then
        verify(exactly = 1) {
            mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
    }

    @Test
    fun `test resetNowPlayingMessage cancels scheduled task`() {
        // Given
        val guildId = 1L
        val mockAudioPlayer = mockk<AudioPlayer>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        every { mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue() } just Runs

        val delay = 0L
        val period = 1L

        every { mockAudioPlayer.volume } returns 50
        every { mockAudioPlayer.isPaused } returns false
        every { mockAudioTrack.info } returns AudioTrackInfo("Test Title", "Test Author", 3000L, "", false, "http://example.com")

        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1)
        nowPlayingManager.scheduleNowPlayingUpdate(guildId, mockAudioTrack, mockAudioPlayer, delay, period)

        scheduler.runPending()

        // When
        nowPlayingManager.resetNowPlayingMessage(guildId)

        scheduler.runPending()

        // Then
        verify(exactly = 1) {
            mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
    }

    @Test
    fun `test clear cancels all scheduled tasks`() {
        // Given
        val guildId1 = 1L
        val guildId2 = 2L
        val mockAudioPlayer1 = mockk<AudioPlayer>(relaxed = true)
        val mockAudioPlayer2 = mockk<AudioPlayer>(relaxed = true)
        val mockAudioTrack1 = mockk<AudioTrack>(relaxed = true)
        val mockAudioTrack2 = mockk<AudioTrack>(relaxed = true)
        every { mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue() } just Runs
        every { mockMessage2.editMessageEmbeds(any<MessageEmbed>()).queue() } just Runs

        val delay = 0L
        val period = 1L

        every { mockAudioPlayer1.volume } returns 50
        every { mockAudioPlayer1.isPaused } returns false
        every { mockAudioPlayer2.volume } returns 50
        every { mockAudioPlayer2.isPaused } returns false
        every { mockAudioTrack1.info } returns AudioTrackInfo("Test Title 1", "Test Author 1", 3000L, "", false, "http://example.com")
        every { mockAudioTrack2.info } returns AudioTrackInfo("Test Title 2", "Test Author 2", 3000L, "", false, "http://example.com")

        nowPlayingManager.setNowPlayingMessage(guildId1, mockMessage1)
        nowPlayingManager.setNowPlayingMessage(guildId2, mockMessage2)
        nowPlayingManager.scheduleNowPlayingUpdate(guildId1, mockAudioTrack1, mockAudioPlayer1, delay, period)
        nowPlayingManager.scheduleNowPlayingUpdate(guildId2, mockAudioTrack2, mockAudioPlayer2, delay, period)

        scheduler.runPending()

        // When
        nowPlayingManager.clear()

        scheduler.runPending()

        // Then
        verify(exactly = 1) {
            mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
        verify(exactly = 1) {
            mockMessage2.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
    }
    /**
     * A now-playing message exists on Discord a round-trip before this class
     * hears about it, and everything that tidies one up keys off having heard.
     * These cover what happens inside that window — which is exactly where a
     * track that dies the moment it starts ends.
     */
    @Test
    fun `a message that lands after the teardown deletes itself`() {
        val guildId = 1L
        val claim = nowPlayingManager.claimNowPlayingSlot(guildId)

        // The track died before the send came back, so the teardown ran first
        // and found nothing to remove.
        nowPlayingManager.resetNowPlayingMessage(guildId)
        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1, claim)

        // Left stored, it would sit in the channel for good with a frozen
        // progress bar and nothing left to come back for it.
        verify { mockMessage1.delete() }
        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `two posts racing leave one message, not two`() {
        val guildId = 1L
        // A retried intro starts before the first send has landed, sees no
        // message to edit, and posts its own.
        val first = nowPlayingManager.claimNowPlayingSlot(guildId)
        val second = nowPlayingManager.claimNowPlayingSlot(guildId)

        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1, first)
        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage2, second)

        verify { mockMessage1.delete() }
        verify(exactly = 0) { mockMessage2.delete() }
        assertEquals(mockMessage2, nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `a claim that is still current stores the message as usual`() {
        val guildId = 1L
        val claim = nowPlayingManager.claimNowPlayingSlot(guildId)

        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1, claim)

        verify(exactly = 0) { mockMessage1.delete() }
        assertEquals(mockMessage1, nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `one guild's teardown does not strand another guild's message`() {
        val claim = nowPlayingManager.claimNowPlayingSlot(1L)

        nowPlayingManager.resetNowPlayingMessage(2L)
        nowPlayingManager.setNowPlayingMessage(1L, mockMessage1, claim)

        verify(exactly = 0) { mockMessage1.delete() }
        assertEquals(mockMessage1, nowPlayingManager.getLastNowPlayingMessage(1L))
    }

    // --- the embed itself ---------------------------------------------------
    //
    // Everything above is about which message is stored and when. None of it
    // touched what the message actually says, which is the part anybody
    // looking at the bot sees.

    private fun playing(
        title: String = "Some Song",
        author: String = "Some Artist",
        duration: Long = 200_000L,
        position: Long = 0L,
        isStream: Boolean = false,
        uri: String = "https://example.com/watch",
        artwork: String? = null,
    ): AudioTrack = mockk(relaxed = true) {
        every { info } returns AudioTrackInfo(title, author, duration, "id", isStream, uri, artwork, null)
        every { this@mockk.position } returns position
        every { this@mockk.duration } returns duration
        every { sourceManager } returns null
    }

    @Test
    fun `the embed names the track, the artist and the volume`() {
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(), volume = 42, isPaused = false,
        )

        assertEquals("Some Song", embed.title)
        assertTrue(embed.description!!.contains("Some Artist"), embed.description)
        assertEquals("🔊 42", embed.fields.single { it.name == "Volume" }.value)
        assertEquals("▶️ No", embed.fields.single { it.name == "Paused" }.value)
    }

    @Test
    fun `a paused track says so`() {
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(), volume = 10, isPaused = true,
        )

        assertEquals("⏸️ Yes", embed.fields.single { it.name == "Paused" }.value)
    }

    @Test
    fun `a live stream gets no progress bar to be wrong about`() {
        // A stream has no length, so a bar would be a lie and the clock would
        // count up forever.
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(isStream = true), volume = 50, isPaused = false,
        )

        assertTrue(embed.description!!.contains("LIVE"), embed.description)
    }

    @Test
    fun `progress on a clipped track is measured against the clip, not the file`() {
        // An intro trimmed to 30s-40s of a four-minute song should read as
        // five seconds into a ten-second clip, not 35 seconds into 3:20.
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(duration = 200_000L, position = 35_000L),
            volume = 50, isPaused = false, clipStart = 30_000L, clipEnd = 40_000L,
        )

        assertTrue(embed.description!!.contains("00:00:05 / 00:00:10"), embed.description)
    }

    @Test
    fun `a position before the clip start does not read as negative`() {
        // The clip start is applied to the track a moment after the embed can
        // first be rendered, so this window is real.
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(position = 0L), volume = 50, isPaused = false, clipStart = 30_000L, clipEnd = 40_000L,
        )

        assertTrue(embed.description!!.contains("00:00:00 / 00:00:10"), embed.description)
    }

    @Test
    fun `what is queued next is listed, and a long queue says how much more`() {
        val scheduler = mockk<TrackScheduler>(relaxed = true)
        every { scheduler.queue } returns java.util.concurrent.LinkedBlockingQueue(
            (1..5).map { playing(title = "Track $it") }
        )
        every { scheduler.getRequesterId(any()) } returns null

        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(), volume = 50, isPaused = false, trackScheduler = scheduler,
        )

        val upNext = embed.fields.single { it.name == "Up next" }.value!!
        assertTrue(upNext.contains("1. `Track 1`"), upNext)
        assertTrue(upNext.contains("3. `Track 3`"), upNext)
        assertTrue(upNext.contains("+ 2 more"), upNext)
    }

    @Test
    fun `a queue that fits says nothing about more`() {
        val scheduler = mockk<TrackScheduler>(relaxed = true)
        every { scheduler.queue } returns java.util.concurrent.LinkedBlockingQueue(listOf(playing(title = "Only")))
        every { scheduler.getRequesterId(any()) } returns null

        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(), volume = 50, isPaused = false, trackScheduler = scheduler,
        )

        assertTrue(!embed.fields.single { it.name == "Up next" }.value!!.contains("more"))
    }

    @Test
    fun `an empty queue gets no up-next field at all`() {
        val scheduler = mockk<TrackScheduler>(relaxed = true)
        every { scheduler.queue } returns java.util.concurrent.LinkedBlockingQueue()

        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(), volume = 50, isPaused = false, trackScheduler = scheduler,
        )

        assertTrue(embed.fields.none { it.name == "Up next" })
    }

    @Test
    fun `a queued title too long to fit is cut rather than blowing the field`() {
        val scheduler = mockk<TrackScheduler>(relaxed = true)
        val long = "x".repeat(120)
        every { scheduler.queue } returns java.util.concurrent.LinkedBlockingQueue(listOf(playing(title = long)))
        every { scheduler.getRequesterId(any()) } returns null

        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(), volume = 50, isPaused = false, trackScheduler = scheduler,
        )

        val upNext = embed.fields.single { it.name == "Up next" }.value!!
        assertTrue(upNext.contains("…"), upNext)
        assertTrue(upNext.length < long.length, upNext)
    }

    @Test
    fun `the footer names whoever asked for the track`() {
        val track = playing()
        val scheduler = mockk<TrackScheduler>(relaxed = true)
        every { scheduler.queue } returns java.util.concurrent.LinkedBlockingQueue()
        every { scheduler.getRequesterId(track) } returns 99L
        val guild = mockk<Guild>(relaxed = true) {
            every { getMemberById(99L) } returns mockk<Member>(relaxed = true) {
                every { effectiveName } returns "Alice"
            }
        }

        val embed = nowPlayingManager.buildNowPlayingMessageData(
            track, volume = 50, isPaused = false, trackScheduler = scheduler, guild = guild,
        )

        assertEquals("Requested by Alice", embed.footer?.text)
    }

    @Test
    fun `a requester who has since left the server is simply not named`() {
        val track = playing()
        val scheduler = mockk<TrackScheduler>(relaxed = true)
        every { scheduler.queue } returns java.util.concurrent.LinkedBlockingQueue()
        every { scheduler.getRequesterId(track) } returns 99L
        val guild = mockk<Guild>(relaxed = true) { every { getMemberById(any<Long>()) } returns null }

        val embed = nowPlayingManager.buildNowPlayingMessageData(
            track, volume = 50, isPaused = false, trackScheduler = scheduler, guild = guild,
        )

        assertNull(embed.footer?.text)
    }

    @Test
    fun `a track with no requester on record gets no footer`() {
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(), volume = 50, isPaused = false,
        )

        assertNull(embed.footer?.text)
    }

    @Test
    fun `an identifier that is not a link is not offered to JDA as one`() {
        // Search terms and local paths reach here as the track uri, and JDA
        // validates anything passed to setTitle or setThumbnail — an unchecked
        // one throws rather than rendering.
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(uri = "ytsearch:something", artwork = "/tmp/local.png"),
            volume = 50, isPaused = false,
        )

        assertNull(embed.url)
        assertNull(embed.thumbnail)
    }

    @Test
    fun `a title longer than Discord allows is cut to fit`() {
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            playing(title = "y".repeat(400)), volume = 50, isPaused = false,
        )

        assertEquals(256, embed.title!!.length)
    }

    @Test
    fun `the volume and paused state are read off the player when one is handed over`() {
        val player = mockk<AudioPlayer>(relaxed = true) {
            every { volume } returns 77
            every { isPaused } returns true
        }

        val embed = nowPlayingManager.buildNowPlayingMessageData(playing(), player)

        assertEquals("🔊 77", embed.fields.single { it.name == "Volume" }.value)
        assertEquals("⏸️ Yes", embed.fields.single { it.name == "Paused" }.value)
    }

}
