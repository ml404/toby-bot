package bot.toby.button.buttons.misc

import bot.toby.command.commands.misc.EightBallCommand
import core.button.ButtonContext
import database.dto.user.UserDto
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class EightBallButtonTest {

    private lateinit var command: EightBallCommand
    private lateinit var button: EightBallButton

    private val event: ButtonInteractionEvent = mockk(relaxed = true)
    private val hook: InteractionHook = mockk(relaxed = true)
    private val ctx: ButtonContext = mockk {
        every { this@mockk.event } returns this@EightBallButtonTest.event
    }
    private val dto: UserDto = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        command = mockk(relaxed = true)
        button = EightBallButton(command)

        every { event.hook } returns hook
        every { event.user.effectiveName } returns "Asker"
        every { event.deferEdit() } returns mockk(relaxed = true) {
            every { queue() } just Runs
        }
    }

    @Test
    fun `defersReply is false so the manager doesn't send an ephemeral followup`() {
        assertEquals(false, button.defersReply)
    }

    @Test
    fun `handle defers as edit and delegates to the command's ask helper`() {
        button.handle(ctx, dto, deleteDelay = 0)

        verify(exactly = 1) { event.deferEdit() }
        verify(exactly = 1) {
            command.ask(hook, dto, "Asker", 0)
        }
    }

    @Test
    fun `name matches the component-id prefix the button stamps onto its reveal embed`() {
        // EightBallEmbeds.ASK_AGAIN_COMPONENT_ID is "8ball:ask" — DefaultButtonManager
        // matches on the colon prefix, so the button name must be exactly "8ball".
        assertEquals("8ball", button.name)
    }
}
