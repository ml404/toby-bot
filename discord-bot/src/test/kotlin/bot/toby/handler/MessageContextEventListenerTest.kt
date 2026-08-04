package bot.toby.handler

import core.managers.MessageContextManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class MessageContextEventListenerTest {

    private val manager: MessageContextManager = mockk(relaxed = true)
    private val listener = MessageContextEventListener(manager)

    private fun event(fromBot: Boolean): MessageContextInteractionEvent = mockk(relaxed = true) {
        every { user } returns mockk<User>(relaxed = true) { every { isBot } returns fromBot }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `an interaction from a person is dispatched`() {
        val e = event(fromBot = false)

        listener.onMessageContextInteraction(e)

        verify(exactly = 1) { manager.handle(e) }
    }

    @Test
    fun `an interaction from a bot is dropped`() {
        val e = event(fromBot = true)

        listener.onMessageContextInteraction(e)

        verify(exactly = 0) { manager.handle(any()) }
    }
}
