package toby.menu

import io.mockk.*
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import toby.command.CommandTest
import toby.command.CommandTest.Companion.guild
import toby.command.CommandTest.Companion.interactionHook
import toby.command.CommandTest.Companion.message
import toby.command.CommandTest.Companion.replyCallbackAction
import toby.command.CommandTest.Companion.user
import toby.command.commands.music.MusicCommandTest.Companion.auditableRestAction

interface MenuTest : CommandTest {
    @BeforeEach
    fun setUpMenuMocks() {
        setUpCommonMocks()
        every { menuEvent.hook } returns interactionHook
        every { menuEvent.deferReply() } returns replyCallbackAction
        every { menuEvent.guild } returns guild
        every { menuEvent.user } returns user
        every { menuEvent.reply(any<String>()) } returns replyCallbackAction
        every { menuEvent.replyFormat(any<String>(), *anyVararg()) } returns replyCallbackAction
        every { menuEvent.message } returns message
        every { message.delete() } returns auditableRestAction as AuditableRestAction<Void>
    }

    @AfterEach
    fun tearDownMenuMocks() {
        tearDownCommonMocks()
        unmockkAll()
    }

    companion object {
        val menuEvent: StringSelectInteractionEvent = mockk(relaxed = true)
    }
}
