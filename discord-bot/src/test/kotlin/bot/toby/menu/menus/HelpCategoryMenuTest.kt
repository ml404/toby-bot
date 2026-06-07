package bot.toby.menu.menus

import bot.toby.command.commands.misc.HelpOverview
import bot.toby.command.commands.music.player.PlayCommand
import core.menu.MenuContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

internal class HelpCategoryMenuTest {

    private val menu = HelpCategoryMenu(listOf(PlayCommand()))

    @Test
    fun `name is the help category menu id`() {
        assertEquals(HelpOverview.MENU_ID, menu.name)
    }

    @Test
    fun `edits the ephemeral message in place with the selected category embed`() {
        val event = mockk<StringSelectInteractionEvent>(relaxed = true)
        val ctx = mockk<MenuContext> { every { this@mockk.event } returns event }
        every { event.selectedOptions } returns listOf(mockk { every { value } returns "music" })

        // JDA's self-referential builders don't survive relaxed-mock chaining,
        // so stub editOriginalEmbeds(...).setComponents(...) to return itself.
        val editAction = mockk<WebhookMessageEditAction<Message>>(relaxed = true)
        every { event.hook.editOriginalEmbeds(any<MessageEmbed>()) } returns editAction
        every { editAction.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns editAction

        // Fire the deferEdit success callback synchronously so the
        // hook.editOriginal chain runs.
        val deferAction = mockk<MessageEditCallbackAction>(relaxed = true)
        every { event.deferEdit() } returns deferAction
        every { deferAction.queue(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg() as Consumer<Any?>).accept(null)
        }

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.hook.editOriginalEmbeds(any<MessageEmbed>()) }
    }
}
