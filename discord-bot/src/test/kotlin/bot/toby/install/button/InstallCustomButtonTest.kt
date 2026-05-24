package bot.toby.install.button

import database.service.guild.ConfigService
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallCustomButtonTest {

    private lateinit var configService: ConfigService
    private lateinit var button: InstallCustomButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        button = InstallCustomButton(configService)
        fx = InstallButtonFixture()
    }

    @Test
    fun `defersReply is false`() {
        assertEquals(false, button.defersReply)
    }

    @Test
    fun `non-owner is rejected with no writes or edits`() {
        fx.asNonOwner()

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.reply(any<String>()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
        verify(exactly = 0) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { fx.event.deferEdit() }
    }

    @Test
    fun `owner happy path performs no DB writes`() {
        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `owner happy path defers edit then swaps to section menu plus bottom row`() {
        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.deferEdit() }
        verify(exactly = 1) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            fx.editAction.setComponents(any<Collection<MessageTopLevelComponent>>())
        }
    }
}
