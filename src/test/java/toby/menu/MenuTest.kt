package toby.menu

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.anyVararg
import toby.command.CommandTest

interface MenuTest : CommandTest {
    @BeforeEach
    fun setUpMenuMocks() {
        setUpCommonMocks()
        Mockito.`when`(menuEvent.hook).thenReturn(CommandTest.interactionHook)
        Mockito.`when`(menuEvent.deferReply())
            .thenReturn(CommandTest.replyCallbackAction)
        Mockito.`when`(menuEvent.deferReply())
            .thenReturn(CommandTest.replyCallbackAction)
        Mockito.`when`<Guild>(menuEvent.guild).thenReturn(CommandTest.guild)
        Mockito.`when`(menuEvent.user).thenReturn(CommandTest.user)
        Mockito.`when`(menuEvent.reply(ArgumentMatchers.anyString()))
            .thenReturn(CommandTest.replyCallbackAction)
        Mockito.`when`(
            menuEvent.replyFormat(
                ArgumentMatchers.anyString(),
                anyVararg()
            )
        ).thenReturn(CommandTest.replyCallbackAction)
    }

    @AfterEach
    fun tearDownMenuMocks() {
        tearDownCommonMocks()
        Mockito.reset(menuEvent)
    }


    companion object {
        @Mock
        val menuEvent: StringSelectInteractionEvent = Mockito.mock(
            StringSelectInteractionEvent::class.java
        )
    }
}
