package bot.toby.handler

import core.managers.ModalManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension

@ExtendWith(MockKExtension::class)
class ModalEventListenerTest {

    private val modalManager: ModalManager = mockk(relaxed = true)
    private val listener = ModalEventListener(modalManager)

    private fun event(isBot: Boolean): ModalInteractionEvent {
        val user = mockk<User>(relaxed = true) { every { this@mockk.isBot } returns isBot }
        return mockk(relaxed = true) { every { this@mockk.user } returns user }
    }

    @Test
    fun `human submission is forwarded to the modal manager after defer`() {
        val event = event(isBot = false)
        every { modalManager.handle(event) } just Runs

        listener.onModalInteraction(event)

        // deferReply happens synchronously before the bot check, so it
        // runs for both humans and bots — we assert it on the human path.
        verify(exactly = 1) { event.deferReply(true) }
        verify(timeout = 1000, exactly = 1) { modalManager.handle(event) }
    }

    @Test
    fun `bot submission is ignored - never delegate to manager`() {
        val event = event(isBot = true)

        listener.onModalInteraction(event)

        // Defer still fires (matches the source's current ordering) but
        // the manager must not see the event.
        verify(exactly = 1) { event.deferReply(true) }
        Thread.sleep(50)
        verify(exactly = 0) { modalManager.handle(any()) }
    }
}
