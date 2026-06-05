package bot.toby.button.buttons.misc

import bot.toby.command.commands.fetch.MemeCommand
import bot.toby.command.commands.fetch.MemeEmbeds
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

internal class MemeButtonTest {

    private lateinit var command: MemeCommand
    private lateinit var button: MemeButton

    private val event: ButtonInteractionEvent = mockk(relaxed = true)
    private val hook: InteractionHook = mockk(relaxed = true)
    private val ctx: ButtonContext = mockk {
        every { this@mockk.event } returns this@MemeButtonTest.event
    }
    private val dto: UserDto = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        command = mockk(relaxed = true)
        button = MemeButton(command)

        every { event.hook } returns hook
        every { event.deferEdit() } returns mockk(relaxed = true) {
            every { queue() } just Runs
        }
        every { dto.memePermission } returns true
    }

    @Test
    fun `defersReply is false so the manager doesn't send an ephemeral followup`() {
        assertEquals(false, button.defersReply)
    }

    @Test
    fun `permitted user triggers a re-fetch with the decoded args`() {
        val row = MemeEmbeds.rerollRow("memes", "week", 25)!!
        val componentId = (row.components.first() as net.dv8tion.jda.api.components.buttons.Button).customId!!
        every { event.componentId } returns componentId

        button.handle(ctx, dto, deleteDelay = 0)

        verify(exactly = 1) { event.deferEdit() }
        verify(exactly = 1) {
            command.fetch(
                hook = hook,
                httpClient = any(),
                subreddit = "memes",
                timePeriod = "week",
                limit = 25,
                deleteDelay = 0,
            )
        }
    }

    @Test
    fun `user without meme permission is silently ignored - no fetch fired`() {
        every { dto.memePermission } returns false
        every { event.componentId } returns "meme:reroll:memes:day:5"

        button.handle(ctx, dto, deleteDelay = 0)

        verify(exactly = 0) {
            command.fetch(
                hook = any(),
                httpClient = any(),
                subreddit = any(),
                timePeriod = any(),
                limit = any(),
                deleteDelay = any(),
            )
        }
    }

    @Test
    fun `name matches the component-id prefix that MemeEmbeds stamps`() {
        assertEquals("meme", button.name)
    }
}
