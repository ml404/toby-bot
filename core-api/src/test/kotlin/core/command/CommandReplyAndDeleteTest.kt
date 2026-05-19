package core.command

import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Verifies that the four reply-and-delete shapes still route through
 * the right JDA action (sendMessage vs sendMessageEmbeds), set the
 * right ephemeral flag, and schedule deletion via the queued callback.
 * All four entry points share a single underlying helper.
 */
class CommandReplyAndDeleteTest {

    private fun stubAction(): WebhookMessageCreateAction<Message> {
        val action = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { action.setEphemeral(any()) } returns action
        return action
    }

    @Test
    fun `replyAndDelete sends plain message and does not touch setEphemeral`() {
        // Non-ephemeral variants must not call setEphemeral — the original
        // helper didn't, and downstream tests mock JDA actions without
        // stubbing setEphemeral(false). See discord-bot IntroHelperTest etc.
        val hook = mockk<InteractionHook>(relaxed = true)
        val action = stubAction()
        every { hook.sendMessage("hello") } returns action

        hook.replyAndDelete("hello", deleteDelay = 5)

        verify(exactly = 1) { hook.sendMessage("hello") }
        verify(exactly = 0) { action.setEphemeral(any()) }
        verify(exactly = 1) { action.queue(any<Consumer<Message>>()) }
    }

    @Test
    fun `replyEphemeralAndDelete sends plain message as ephemeral`() {
        val hook = mockk<InteractionHook>(relaxed = true)
        val action = stubAction()
        every { hook.sendMessage("hi") } returns action

        hook.replyEphemeralAndDelete("hi", deleteDelay = 1)

        verify(exactly = 1) { hook.sendMessage("hi") }
        verify(exactly = 1) { action.setEphemeral(true) }
        verify(exactly = 1) { action.queue(any<Consumer<Message>>()) }
    }

    @Test
    fun `replyEmbedAndDelete sends an embed and does not touch setEphemeral`() {
        val hook = mockk<InteractionHook>(relaxed = true)
        val embed = mockk<MessageEmbed>(relaxed = true)
        val action = stubAction()
        every { hook.sendMessageEmbeds(embed) } returns action

        hook.replyEmbedAndDelete(embed, deleteDelay = 3)

        verify(exactly = 1) { hook.sendMessageEmbeds(embed) }
        verify(exactly = 0) { action.setEphemeral(any()) }
        verify(exactly = 1) { action.queue(any<Consumer<Message>>()) }
    }

    @Test
    fun `replyEphemeralEmbedAndDelete sends an embed as ephemeral`() {
        val hook = mockk<InteractionHook>(relaxed = true)
        val embed = mockk<MessageEmbed>(relaxed = true)
        val action = stubAction()
        every { hook.sendMessageEmbeds(embed) } returns action

        hook.replyEphemeralEmbedAndDelete(embed, deleteDelay = 2)

        verify(exactly = 1) { hook.sendMessageEmbeds(embed) }
        verify(exactly = 1) { action.setEphemeral(true) }
        verify(exactly = 1) { action.queue(any<Consumer<Message>>()) }
    }

    @Test
    fun `queued callback schedules deletion on the returned message`() {
        val hook = mockk<InteractionHook>(relaxed = true)
        val action = stubAction()
        every { hook.sendMessage(any<String>()) } returns action

        val consumerSlot = slot<Consumer<Message>>()
        every { action.queue(capture(consumerSlot)) } returns Unit

        hook.replyAndDelete("msg", deleteDelay = 7)

        assertTrue(consumerSlot.isCaptured)
        val msg = mockk<Message>(relaxed = true)
        consumerSlot.captured.accept(msg)
        verify(exactly = 1) { msg.delete() }
    }

    @Test
    fun `string-shape uses sendMessage and never sendMessageEmbeds`() {
        val hook = mockk<InteractionHook>(relaxed = true)
        val action = stubAction()
        every { hook.sendMessage(any<String>()) } returns action

        hook.replyAndDelete("only-text", 0)

        verify(exactly = 0) { hook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `embed-shape uses sendMessageEmbeds and never sendMessage`() {
        val hook = mockk<InteractionHook>(relaxed = true)
        val embed = mockk<MessageEmbed>(relaxed = true)
        val action = stubAction()
        every { hook.sendMessageEmbeds(embed) } returns action

        hook.replyEmbedAndDelete(embed, 0)

        verify(exactly = 0) { hook.sendMessage(any<String>()) }
    }
}
