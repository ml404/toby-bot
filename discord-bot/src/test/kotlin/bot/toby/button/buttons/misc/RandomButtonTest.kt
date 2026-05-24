package bot.toby.button.buttons.misc

import bot.toby.command.commands.misc.RandomCommand
import bot.toby.command.commands.misc.RandomEmbeds
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

internal class RandomButtonTest {

    private lateinit var command: RandomCommand
    private lateinit var button: RandomButton

    private val event: ButtonInteractionEvent = mockk(relaxed = true)
    private val hook: InteractionHook = mockk(relaxed = true)
    private val ctx: ButtonContext = mockk {
        every { this@mockk.event } returns this@RandomButtonTest.event
    }
    private val dto: UserDto = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        command = mockk(relaxed = true)
        button = RandomButton(command)

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
    fun `handle decodes options from the component id and delegates to RandomCommand#pick`() {
        val options = listOf("pizza", "burger", "tacos")
        val componentId = (RandomEmbeds.pickAgainRow(options)!!.components.first()
                as net.dv8tion.jda.api.components.buttons.Button).customId!!
        every { event.componentId } returns componentId

        button.handle(ctx, dto, deleteDelay = 0)

        verify(exactly = 1) { event.deferEdit() }
        verify(exactly = 1) { command.pick(hook, options, "Asker", 0) }
    }

    @Test
    fun `name matches the component-id prefix that RandomEmbeds stamps`() {
        assertEquals("random", button.name)
    }
}
