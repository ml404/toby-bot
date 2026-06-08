package bot.toby.install.button

import bot.toby.install.InstallCompletionService
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallFinishButtonTest {

    private lateinit var installCompletionService: InstallCompletionService
    private lateinit var button: InstallFinishButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        installCompletionService = mockk(relaxed = true)
        button = InstallFinishButton(installCompletionService)
        fx = InstallButtonFixture()
    }

    @Test
    fun `defersReply is false`() {
        assertEquals(false, button.defersReply)
    }

    @Test
    fun `non-owner is rejected with no completion or edits`() {
        fx.asNonOwner()

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.reply(any<String>()) }
        verify(exactly = 0) { installCompletionService.complete(any(), any(), any()) }
        verify(exactly = 0) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `owner happy path delegates completion in custom mode`() {
        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { installCompletionService.complete(fx.guild, "custom", any()) }
    }

    @Test
    fun `owner happy path defers edit then shows finish-done embed with launcher components`() {
        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.deferEdit() }
        verify(exactly = 1) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            fx.editAction.setComponents(*anyVararg<MessageTopLevelComponent>())
        }
        verify(exactly = 1) { fx.editAction.queue() }
    }

    @Test
    fun `owner happy path pins the done message as a control panel`() {
        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.message.pin() }
    }

    @Test
    fun `non-owner does not pin`() {
        fx.asNonOwner()

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 0) { fx.message.pin() }
    }
}
