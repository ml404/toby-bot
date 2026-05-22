package bot.toby.install.button

import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallFinishButtonTest {

    private lateinit var configService: ConfigService
    private lateinit var button: InstallFinishButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        button = InstallFinishButton(configService)
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
    }

    @Test
    fun `owner happy path writes INSTALL_MODE custom and a fresh INSTALLED_AT epoch`() {
        val installedAtSlot = slot<String>()
        every {
            configService.upsertConfig(Configurations.INSTALLED_AT.configValue, capture(installedAtSlot), "g1")
        } returns mockk(relaxed = true)

        val before = System.currentTimeMillis()
        button.handle(fx.ctx, mockk(relaxed = true), 0)
        val after = System.currentTimeMillis()

        verify(exactly = 1) {
            configService.upsertConfig(Configurations.INSTALL_MODE.configValue, "custom", "g1")
        }
        verify(exactly = 1) {
            configService.upsertConfig(Configurations.INSTALLED_AT.configValue, any<String>(), "g1")
        }
        val capturedEpoch = installedAtSlot.captured.toLong()
        assertTrue(capturedEpoch in before..after, "INSTALLED_AT epoch should be ~now")
    }

    @Test
    fun `owner happy path defers edit then shows finish-done embed with stripped components`() {
        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.deferEdit() }
        verify(exactly = 1) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            fx.editAction.setComponents(*anyVararg<MessageTopLevelComponent>())
        }
        verify(exactly = 1) { fx.editAction.queue() }
    }
}
