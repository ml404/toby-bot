package bot.toby.install.button

import bot.toby.command.commands.music.player.PlayCommand
import bot.toby.install.InstallWizard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallHelpButtonTest {

    private lateinit var button: InstallHelpButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        button = InstallHelpButton(listOf(PlayCommand()))
        fx = InstallButtonFixture()
    }

    @Test
    fun `name matches the public help button id`() {
        assertEquals(InstallWizard.BTN_HELP, button.name)
    }

    @Test
    fun `defersReply is true so the manager acks ephemerally`() {
        // Unlike the owner-only install buttons (which defer an edit), the
        // help button replies with a fresh ephemeral message.
        assertEquals(true, button.defersReply)
    }

    @Test
    fun `sends the overview embed to anyone with no owner gate`() {
        // A non-owner must still get the overview — that's the whole point.
        fx.asNonOwner()

        // JDA's self-referential builders don't survive relaxed-mock chaining,
        // so stub sendMessageEmbeds(...).addComponents(...) to return itself.
        val createAction = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { fx.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) } returns createAction
        every { createAction.addComponents(*anyVararg<MessageTopLevelComponent>()) } returns createAction

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 1) { createAction.addComponents(*anyVararg<MessageTopLevelComponent>()) }
        verify(exactly = 0) { fx.event.reply(any<String>()) }
    }
}
