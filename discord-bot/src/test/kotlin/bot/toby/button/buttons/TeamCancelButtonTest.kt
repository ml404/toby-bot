package bot.toby.button.buttons

import bot.toby.command.commands.misc.TeamCommand
import core.button.ButtonContext
import database.service.TeamSplitSessionService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TeamCancelButtonTest {

    private lateinit var sessionService: TeamSplitSessionService
    private lateinit var button: TeamCancelButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var hook: InteractionHook
    private val sessionId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        sessionService = mockk(relaxed = true)
        button = TeamCancelButton(sessionService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "${TeamCommand.BUTTON_CANCEL}:$sessionId"
            every { deferEdit() } returns mockk(relaxed = true)
            every { this@mockk.hook } returns this@TeamCancelButtonTest.hook
        }
        ctx = mockk {
            every { this@mockk.event } returns this@TeamCancelButtonTest.event
            every { this@mockk.guild } returns mockk<Guild>(relaxed = true)
        }

        // Explicit chain stubs: relaxed mocks lose self-type on JDA's
        // MessageEditRequest<R> chain, so each step needs to return the
        // typed WebhookMessageEditAction mock for the next call.
        @Suppress("UNCHECKED_CAST")
        val editAction = mockk<WebhookMessageEditAction<Message>>(relaxed = true)
        every { hook.editOriginal(any<String>()) } returns editAction
        every { editAction.setEmbeds(any<Collection<MessageEmbed>>()) } returns editAction
        every { editAction.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns editAction
        every { editAction.queue() } just Runs
    }

    @Test
    fun `marks the session cancelled and clears the message`() {
        button.handle(ctx, mockk(relaxed = true), 0)

        verify { sessionService.markCancelled(sessionId) }
        verify { hook.editOriginal(any<String>()) }
    }

    @Test
    fun `tolerates a bogus component id without throwing`() {
        every { event.componentId } returns "${TeamCommand.BUTTON_CANCEL}:not-a-uuid"

        button.handle(ctx, mockk(relaxed = true), 0)

        verify(exactly = 0) { sessionService.markCancelled(any()) }
        verify { hook.editOriginal(any<String>()) }
    }
}
