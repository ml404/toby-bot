package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The bridge lavaplayer's audio crosses to reach Discord. Every intro and
 * every queued track goes through these three methods, and none of them was
 * executed by any test — a break here is not a broken intro, it is the whole
 * bot going quiet with nothing in the logs to say so.
 */
class AudioPlayerSendHandlerTest {

    private lateinit var player: AudioPlayer
    private lateinit var handler: AudioPlayerSendHandler

    @BeforeEach
    fun setUp() {
        player = mockk()
        handler = AudioPlayerSendHandler(player)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `it offers audio exactly when the player has some`() {
        every { player.provide(any<MutableAudioFrame>()) } returns true
        assertTrue(handler.canProvide())

        every { player.provide(any<MutableAudioFrame>()) } returns false
        assertFalse(handler.canProvide())
    }

    @Test
    fun `the frame it offers the player is backed by the buffer it hands back`() {
        // The two halves have to agree: JDA calls canProvide, which fills the
        // frame, and then takes whatever provide20MsAudio returns. If the frame
        // wrote somewhere else the bot would send silence forever.
        val frame = slot<MutableAudioFrame>()
        every { player.provide(capture(frame)) } answers {
            // store() is how lavaplayer itself fills the frame it was handed.
            frame.captured.store(byteArrayOf(1, 2, 3, 4), 0, 4)
            true
        }

        handler.canProvide()
        val out = handler.provide20MsAudio()

        assertEquals(4, out.remaining())
        assertEquals(1.toByte(), out.get())
    }

    @Test
    fun `it hands back a buffer flipped for reading, not for writing`() {
        // Without the flip JDA reads from the write position and sends nothing.
        every { player.provide(any<MutableAudioFrame>()) } answers {
            firstArg<MutableAudioFrame>().store(ByteArray(8), 0, 8)
            true
        }

        handler.canProvide()
        val out = handler.provide20MsAudio()

        assertEquals(0, out.position())
        assertEquals(8, out.limit())
    }

    @Test
    fun `it declares opus, because lavaplayer hands over opus`() {
        // Saying false makes JDA try to encode already-encoded audio.
        assertTrue(handler.isOpus)
    }
}
