package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.DefaultCommandContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SupportCommandTest : CommandTest {
    private lateinit var supportCommand: SupportCommand

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        every { event.deferReply(true) } returns replyCallbackAction
        supportCommand = SupportCommand()
    }

    @Test
    fun testHandleSendsSupportEmbed() {
        supportCommand.handle(DefaultCommandContext(event), mockk(), 0)

        verify(exactly = 1) { interactionHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }
}
