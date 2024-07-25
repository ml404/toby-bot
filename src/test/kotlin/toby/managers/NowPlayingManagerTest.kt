package toby.managers

import io.mockk.*
import net.dv8tion.jda.api.entities.Message
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
    fun tearDown(){
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

        every { mockMessage1.delete().queue(any(), any()) } just Runs

        nowPlayingManager.resetNowPlayingMessage(guildId)
        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Verify that delete().queue was called
        verify { mockMessage1.delete().queue() }
    }

    @Test
    fun `test resetting non-existent message`() {
        val guildId = 1L
        nowPlayingManager.setNowPlayingMessage(guildId, mockMessage1)

        every { mockMessage1.delete().queue(any(), any()) } just Runs

        nowPlayingManager.resetNowPlayingMessage(guildId)
        nowPlayingManager.resetNowPlayingMessage(guildId) // Reset again should be a no-op
        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Verify that delete().queue was called once
        verify(exactly = 1) { mockMessage1.delete().queue() }
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

        // Set the first message
        nowPlayingManager.setNowPlayingMessage(guildId, message1)

        // Verify that the first message is set
        assertEquals(message1, nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Clear the messages
        nowPlayingManager.resetNowPlayingMessage(guildId)

        // Verify that the message is cleared
        assertNull(nowPlayingManager.getLastNowPlayingMessage(guildId))

        // Set the second message
        nowPlayingManager.setNowPlayingMessage(guildId, message2)

        // Verify that the second message is correctly set
        assertEquals(message2, nowPlayingManager.getLastNowPlayingMessage(guildId))
    }
}
