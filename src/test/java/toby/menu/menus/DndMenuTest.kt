package toby.menu.menus

import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import toby.command.CommandTest
import toby.menu.MenuContext
import toby.menu.MenuTest

internal class DndMenuTest : MenuTest {
    private var dndMenu: DndMenu? = null

    @BeforeEach
    fun setup() {
        setUpMenuMocks()
        dndMenu = DndMenu()
        Mockito.doReturn(CommandTest.webhookMessageCreateAction).`when`(CommandTest.interactionHook)
            .sendMessageEmbeds(
                any(),
                anyVararg()
            )
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
        dndMenu!!.handle(ctx, 0)

        //Assert
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).deferReply()
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).hook
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(any(), anyVararg())
    }

    @Test
    fun test_dndMenuWithCondition() {
        //Arrange
        val ctx = mockAndCreateMenuContext("dnd:condition", "grappled")

        //Act
        dndMenu!!.handle(ctx, 0)

        //Assert
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).deferReply()
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).hook
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(any(), anyVararg())
    }

    @Test
    fun test_dndMenuWithRule() {
        //Arrange
        val ctx = mockAndCreateMenuContext("dnd:rule", "cover")

        //Act
        dndMenu!!.handle(ctx, 0)

        //Assert
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).deferReply()
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).hook
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(any(), anyVararg())
    }

    @Test
    fun test_dndMenuWithFeature() {
        //Arrange
        val ctx = mockAndCreateMenuContext("dnd:feature", "action-surge-1-use")

        //Act
        dndMenu!!.handle(ctx, 0)

        //Assert
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).deferReply()
        Mockito.verify(MenuTest.menuEvent, Mockito.times(1)).hook
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(any(), anyVararg())
    }


    companion object {
        private fun mockAndCreateMenuContext(eventName: String, selectedValue: String): MenuContext {
            val auditableRestAction = Mockito.mock(AuditableRestAction::class.java)
            Mockito.`when`(MenuTest.menuEvent.componentId).thenReturn(eventName)
            Mockito.`when`<List<String>>(MenuTest.menuEvent.values).thenReturn(listOf(selectedValue))
            Mockito.`when`(MenuTest.menuEvent.message).thenReturn(CommandTest.message)
            Mockito.`when`<AuditableRestAction<Void>>(CommandTest.message.delete())
                .thenReturn(auditableRestAction as AuditableRestAction<Void>)
            return MenuContext(MenuTest.menuEvent)
        }
    }
}