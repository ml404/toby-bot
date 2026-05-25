package bot.toby.managers

import core.autocomplete.AutocompleteHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DefaultAutoCompleteManagerTest {

    private class FakeHandler(override val name: String) : AutocompleteHandler {
        override fun handle(event: CommandAutoCompleteInteractionEvent) = Unit
    }

    private fun manager(vararg handlers: AutocompleteHandler) =
        DefaultAutoCompleteManager(handlers.toList())

    @Test
    fun `getHandler resolves by exact command name`() {
        val dnd = FakeHandler("dnd")
        val mgr = manager(dnd)

        assertEquals(dnd, mgr.getHandler("dnd"))
    }

    @Test
    fun `getHandler matching is case-insensitive`() {
        val dnd = FakeHandler("dnd")
        val mgr = manager(dnd)

        assertEquals(dnd, mgr.getHandler("DnD"))
    }

    @Test
    fun `getHandler returns null when no handler matches`() {
        val mgr = manager(FakeHandler("dnd"), FakeHandler("help"))

        assertNull(mgr.getHandler("nonexistent"))
    }

    @Test
    fun `handle routes the event to the handler whose name matches event name`() {
        val dnd = mockk<AutocompleteHandler>(relaxed = true) { every { name } returns "dnd" }
        val help = mockk<AutocompleteHandler>(relaxed = true) { every { name } returns "help" }
        val mgr = manager(dnd, help)
        val event = mockk<CommandAutoCompleteInteractionEvent>(relaxed = true) {
            every { name } returns "dnd"
        }

        mgr.handle(event)

        verify(exactly = 1) { dnd.handle(event) }
        verify(exactly = 0) { help.handle(any()) }
    }

    @Test
    fun `handle is a no-op when no handler matches the event name`() {
        val dnd = mockk<AutocompleteHandler>(relaxed = true) { every { name } returns "dnd" }
        val mgr = manager(dnd)
        val event = mockk<CommandAutoCompleteInteractionEvent>(relaxed = true) {
            every { name } returns "nonexistent"
        }

        mgr.handle(event)

        verify(exactly = 0) { dnd.handle(any()) }
    }
}
