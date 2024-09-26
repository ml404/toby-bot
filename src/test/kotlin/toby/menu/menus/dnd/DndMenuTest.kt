package toby.menu.menus.dnd

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper
import toby.menu.MenuContext

@OptIn(ExperimentalCoroutinesApi::class)
class DndMenuTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dndMenu: DndMenu
    private lateinit var handler: DndApiCoroutineHandler
    private lateinit var processor: DndEventProcessor
    private lateinit var httpHelper: HttpHelper
    private lateinit var dndHelper: DnDHelper

    @BeforeEach
    fun setup() {
        httpHelper = mockk()
        dndHelper = mockk()
        handler = mockk(relaxed = true)
        processor = mockk(relaxed = true)
        dndMenu = DndMenu(dispatcher, httpHelper, dndHelper, handler, processor)
    }

    @Test
    fun `handle should call handler with correct parameters`() = runTest(dispatcher) {
        // Arrange
        val ctx = mockk<MenuContext>(relaxed = true)
        val event = mockk<StringSelectInteractionEvent>(relaxed = true)
        val hook = mockk<InteractionHook>(relaxed = true)

        every { ctx.event } returns event
        every { event.hook } returns hook
        every { processor.toTypeString(event) } returns "spell"
        every { processor.determineTypeValue("spell") } returns "spells"

        // Act
        dndMenu.handle(ctx, 0)

        // Assert
        verify {
            handler.launchFetchAndSendEmbed(event, "spell", "spells", hook)
        }
    }
}
