package bot.toby.handler

import core.managers.ButtonManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guards the exception safety net so a button handler that throws after
 * the manager's auto-defer does not leave the user staring at a hanging
 * "Bot is thinking…" spinner.
 */
class ButtonEventListenerTest {

    private lateinit var buttonManager: ButtonManager
    private lateinit var listener: ButtonEventListener

    @BeforeEach
    fun setup() {
        buttonManager = mockk()
        listener = ButtonEventListener(buttonManager, Dispatchers.Unconfined)
    }

    private fun event(acknowledged: Boolean): ButtonInteractionEvent {
        val user: User = mockk(relaxed = true) {
            every { isBot } returns false
        }
        val hook: InteractionHook = mockk(relaxed = true)
        val event: ButtonInteractionEvent = mockk(relaxed = true)
        every { event.user } returns user
        every { event.hook } returns hook
        every { event.componentId } returns "broken-button"
        every { event.isAcknowledged } returns acknowledged
        return event
    }

    @Test
    fun `button throw after defer edits the ephemeral spinner with an error`() {
        val event = event(acknowledged = true)
        val editAction: WebhookMessageEditAction<*> = mockk(relaxed = true)
        every { event.hook.editOriginal(any<String>()) } returns
            editAction as WebhookMessageEditAction<net.dv8tion.jda.api.entities.Message>
        every { buttonManager.handle(event) } throws RuntimeException("boom")

        listener.onButtonInteraction(event)

        verify(exactly = 1) {
            event.hook.editOriginal(match<String> { it.contains("Something went wrong") })
        }
    }

    @Test
    fun `button throw before any ack falls back to ephemeral reply`() {
        val event = event(acknowledged = false)
        every { event.reply(any<String>()).setEphemeral(any()) } returns mockk(relaxed = true)
        every { buttonManager.handle(event) } throws RuntimeException("boom")

        listener.onButtonInteraction(event)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("Something went wrong") })
        }
    }

    @Test
    fun `happy path does not touch the error path`() {
        val event = event(acknowledged = true)
        every { buttonManager.handle(event) } just Runs

        listener.onButtonInteraction(event)

        verify(exactly = 0) { event.hook.editOriginal(any<String>()) }
        verify(exactly = 1) { buttonManager.handle(event) }
    }
}
