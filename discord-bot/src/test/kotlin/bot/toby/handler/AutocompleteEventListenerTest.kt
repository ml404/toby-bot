package bot.toby.handler

import core.managers.AutocompleteManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension

@ExtendWith(MockKExtension::class)
class AutocompleteEventListenerTest {

    private val autocompleteManager: AutocompleteManager = mockk()
    private val listener = AutocompleteEventListener(autocompleteManager)

    @Test
    fun `delegates to autocompleteManager`() {
        val event = mockk<CommandAutoCompleteInteractionEvent>()
        every { autocompleteManager.handle(event) } just Runs

        listener.onCommandAutoCompleteInteraction(event)

        verify { autocompleteManager.handle(event) }
    }
}
