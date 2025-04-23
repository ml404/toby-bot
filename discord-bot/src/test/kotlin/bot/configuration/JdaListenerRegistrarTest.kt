package bot.configuration

import bot.toby.handler.EventWaiter
import bot.toby.handler.MessageEventHandler
import bot.toby.handler.StartUpHandler
import bot.toby.handler.VoiceEventHandler
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Test

class JdaListenerRegistrarTest {

    @Test
    fun `should register all event listeners with JDA`() {
        // Arrange
        val jda = mockk<JDA>(relaxed = true)
        val startUpHandler = mockk<StartUpHandler>()
        val voiceEventHandler = mockk<VoiceEventHandler>()
        val messageEventHandler = mockk<MessageEventHandler>()
        val eventWaiter = mockk<EventWaiter>()

        // Act
        JdaListenerRegistrar(
            jda,
            startUpHandler,
            voiceEventHandler,
            messageEventHandler,
            eventWaiter
        )

        // Assert
        verify {
            jda.addEventListener(
                startUpHandler,
                voiceEventHandler,
                messageEventHandler,
                eventWaiter
            )
        }
    }
}
