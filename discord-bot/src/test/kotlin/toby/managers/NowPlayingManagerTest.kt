package toby.managers

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
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
    private lateinit var mockMessage1: Message
    private lateinit var mockMessage2: Message

    @BeforeEach
    fun setUp() {
        nowPlayingManager = NowPlayingManager()
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

        // Sleep to allow the scheduled task to run
        Thread.sleep(200)

        // Then
        coVerify(atLeast = 1) {
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

        // Sleep to allow the scheduled task to run
        Thread.sleep(200)

        // Then
        coVerify(atLeast = 1) {
            mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
        coVerify(atLeast = 1) {
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

        // Sleep to allow the scheduled task to run
        Thread.sleep(200)

        // When
        nowPlayingManager.cancelScheduledTask(guildId)

        // Sleep to allow any pending tasks to be cancelled
        Thread.sleep(200)

        // Then
        coVerify(atMost = 1) {
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

        // Sleep to allow the scheduled task to run
        Thread.sleep(200)

        // When
        nowPlayingManager.resetNowPlayingMessage(guildId)

        // Sleep to allow any pending tasks to be cancelled
        Thread.sleep(200)

        // Then
        coVerify(atMost = 1) {
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

        // Sleep to allow the scheduled task to run
        Thread.sleep(200)

        // When
        nowPlayingManager.clear()

        // Sleep to allow any pending tasks to be cancelled
        Thread.sleep(200)

        // Then
        coVerify(atMost = 1) {
            mockMessage1.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
        coVerify(atMost = 1) {
            mockMessage2.editMessageEmbeds(any<MessageEmbed>()).queue()
        }
    }
}
