package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SupportCommandTest : CommandTest {
    private lateinit var supportCommand: SupportCommand

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        every { event.deferReply(true) } returns replyCallbackAction
        every { event.hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        supportCommand = SupportCommand()
    }

    @Test
    fun testHandleSendsSupportEmbed() {
        supportCommand.handle(DefaultCommandContext(event), mockk(), 0)

        verify(exactly = 1) { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
    }
}
