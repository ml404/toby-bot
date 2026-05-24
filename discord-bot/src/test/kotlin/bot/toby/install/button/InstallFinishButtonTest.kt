package bot.toby.install.button

import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
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
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
        verify(exactly = 0) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `owner happy path writes INSTALL_MODE custom and INSTALLED_AT epoch via batch upsert`() {
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        val before = System.currentTimeMillis()
        button.handle(fx.ctx, mockk(relaxed = true), 0)
        val after = System.currentTimeMillis()

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        val rows = rowsSlot.captured
        assertTrue(rows.size == 2)
        assertTrue(rows[0] == Configurations.INSTALL_MODE.configValue to "custom")
        assertTrue(rows[1].first == Configurations.INSTALLED_AT.configValue)
        assertTrue(rows[1].second.toLong() in before..after, "INSTALLED_AT epoch should be ~now")
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
