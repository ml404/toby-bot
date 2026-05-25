package bot.toby.handler

import core.managers.MenuManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension

@ExtendWith(MockKExtension::class)
class MenuEventListenerTest {

    private val menuManager: MenuManager = mockk(relaxed = true)
    private val listener = MenuEventListener(menuManager)

    @Test
    fun `string-select events are forwarded to the menu manager`() {
        val event = mockk<StringSelectInteractionEvent>(relaxed = true) {
            every { componentId } returns "intro:set"
        }
        every { menuManager.handle(event) } just Runs

        listener.onStringSelectInteraction(event)

        verify(timeout = 1000, exactly = 1) { menuManager.handle(event) }
    }
}
