package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledExecutorService

class PlayerManagerTest {

    private lateinit var apm: AudioPlayerManager
    private lateinit var guild: Guild
    private lateinit var event: SlashCommandInteractionEvent
    private val handlers = mutableListOf<AudioLoadResultHandler>()
    private lateinit var pm: PlayerManager

    @BeforeEach
    fun setUp() {
        apm = mockk(relaxed = true)
        handlers.clear()
        val handlerSlot = slot<AudioLoadResultHandler>()
        every { apm.loadItemOrdered(any<Any>(), any<String>(), capture(handlerSlot)) } answers {
            handlers.add(handlerSlot.captured)
            mockk<java.util.concurrent.Future<Void>>(relaxed = true)
        }
        // Run scheduled retries synchronously so the test is deterministic.
        val immediate = mockk<ScheduledExecutorService>()
        every { immediate.schedule(any<Runnable>(), any<Long>(), any<java.util.concurrent.TimeUnit>()) } answers {
            firstArg<Runnable>().run()
            mockk<java.util.concurrent.ScheduledFuture<*>>(relaxed = true)
        }
        pm = PlayerManager(apm, immediate)
        guild = mockk(relaxed = true) { every { idLong } returns 1L }
        event = mockk(relaxed = true)
    }

    private fun transientFailure() =
        FriendlyException("rate limited", FriendlyException.Severity.SUSPICIOUS, RuntimeException("x"))

    @Test
    fun `transient load failures are retried up to the attempt cap`() {
        pm.loadAndPlay(guild, event, "url", true, 5, 0L, 50, null)
        assertEquals(1, handlers.size)

        handlers.last().loadFailed(transientFailure())
        assertEquals(2, handlers.size, "first transient failure should trigger a retry")

        handlers.last().loadFailed(transientFailure())
        assertEquals(3, handlers.size, "second transient failure should trigger another retry")

        handlers.last().loadFailed(transientFailure())
        assertEquals(3, handlers.size, "at the cap no further load is attempted")

        verify(exactly = PlayerManager.MAX_LOAD_ATTEMPTS) { apm.loadItemOrdered(any<Any>(), any<String>(), any<AudioLoadResultHandler>()) }
    }

    @Test
    fun `common track-specific failures are not retried`() {
        pm.loadAndPlay(guild, event, "url", true, 5, 0L, 50, null)

        handlers.last().loadFailed(
            FriendlyException("video unavailable", FriendlyException.Severity.COMMON, RuntimeException("x")),
        )

        assertEquals(1, handlers.size)
        verify(exactly = 1) { apm.loadItemOrdered(any<Any>(), any<String>(), any<AudioLoadResultHandler>()) }
    }
}
