package toby.menu.menus

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandTest
import toby.menu.MenuContext
import toby.menu.MenuTest

internal class DndMenuTest : MenuTest {
    private lateinit var dndMenu: DndMenu

    @BeforeEach
    fun setup() {
        setUpMenuMocks()
        dndMenu = DndMenu()
        every { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) } returns CommandTest.webhookMessageCreateAction
    }

    @AfterEach
    fun tearDown() {
        tearDownMenuMocks()
    }

    @Test
    fun test_dndMenuWithSpell() {
        //Arrange
        val ctx = mockAndCreateMenuContext("dnd:spell", "fireball")

        //Act
        dndMenu.handle(ctx, 0)

        //Assert
        verify(exactly = 1) { MenuTest.menuEvent.deferReply() }
        verify(exactly = 1) { MenuTest.menuEvent.hook }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun test_dndMenuWithCondition() {
        //Arrange
        val ctx = mockAndCreateMenuContext("dnd:condition", "grappled")

        //Act
        dndMenu.handle(ctx, 0)

        //Assert
        verify(exactly = 1) { MenuTest.menuEvent.deferReply() }
        verify(exactly = 1) { MenuTest.menuEvent.hook }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun test_dndMenuWithRule() {
        //Arrange
        val ctx = mockAndCreateMenuContext("dnd:rule", "cover")

        //Act
        dndMenu.handle(ctx, 0)

        //Assert
        verify(exactly = 1) { MenuTest.menuEvent.deferReply() }
        verify(exactly = 1) { MenuTest.menuEvent.hook }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun test_dndMenuWithFeature() {
        //Arrange
        val ctx = mockAndCreateMenuContext("dnd:feature", "action-surge-1-use")

        //Act
        dndMenu.handle(ctx, 0)

        //Assert
        verify(exactly = 1) { MenuTest.menuEvent.deferReply() }
        verify(exactly = 1) { MenuTest.menuEvent.hook }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    companion object {
        private fun mockAndCreateMenuContext(eventName: String, selectedValue: String): MenuContext {
            val auditableRestAction = mockk<AuditableRestAction<Void>>()
            every { MenuTest.menuEvent.componentId } returns eventName
            every { MenuTest.menuEvent.values } returns listOf(selectedValue)
            every { MenuTest.menuEvent.message } returns CommandTest.message
            every { CommandTest.message.delete() } returns auditableRestAction
            return MenuContext(MenuTest.menuEvent)
        }
    }
}
