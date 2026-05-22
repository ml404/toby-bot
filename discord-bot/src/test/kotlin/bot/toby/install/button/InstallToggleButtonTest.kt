package bot.toby.install.button

import bot.toby.install.InstallWizard
import bot.toby.install.OptInFeatures
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class InstallToggleButtonTest {

    private lateinit var configService: ConfigService
    private lateinit var button: InstallToggleButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        button = InstallToggleButton(configService)
        fx = InstallButtonFixture()
        // event.message.components is left as the default relaxed mock
        // return (empty list). The toggle button's bottom-row resolver
        // falls back to doneButtonRow() in that case, which is fine for
        // every assertion in this class (we don't inspect the bottom
        // row directly — the fallback path is covered in
        // `falls back to doneButtonRow when no second row on message`).
    }

    @Test
    fun `defersReply is false`() {
        assertEquals(false, button.defersReply)
    }

    @Test
    fun `non-owner is rejected with no writes or edits`() {
        fx.asNonOwner()
        every { fx.event.componentId } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:ACTIVITY_TRACKING"

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.reply(any<String>()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
        verify(exactly = 0) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `unknown suffix is rejected ephemerally with no writes`() {
        every { fx.event.componentId } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:NOPE"

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.reply(any<String>()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @ParameterizedTest
    @EnumSource(OptInFeatures::class)
    fun `true flips to false for every opt-in feature`(feature: OptInFeatures) {
        every { fx.event.componentId } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:${feature.key.name}"
        every {
            configService.getConfigByName(feature.key.configValue, "g1")
        } returns ConfigDto(feature.key.configValue, "true", "g1")

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) {
            configService.upsertConfig(feature.key.configValue, "false", "g1")
        }
    }

    @ParameterizedTest
    @EnumSource(OptInFeatures::class)
    fun `false flips to true for every opt-in feature`(feature: OptInFeatures) {
        every { fx.event.componentId } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:${feature.key.name}"
        every {
            configService.getConfigByName(feature.key.configValue, "g1")
        } returns ConfigDto(feature.key.configValue, "false", "g1")

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) {
            configService.upsertConfig(feature.key.configValue, "true", "g1")
        }
    }

    @ParameterizedTest
    @EnumSource(OptInFeatures::class)
    fun `null is treated as off and flips to true`(feature: OptInFeatures) {
        every { fx.event.componentId } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:${feature.key.name}"
        every { configService.getConfigByName(feature.key.configValue, "g1") } returns null

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) {
            configService.upsertConfig(feature.key.configValue, "true", "g1")
        }
    }

    @Test
    fun `any non-true value is treated as off and flips to true`() {
        val feature = OptInFeatures.ACTIVITY_TRACKING
        every { fx.event.componentId } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:${feature.key.name}"
        every {
            configService.getConfigByName(feature.key.configValue, "g1")
        } returns ConfigDto(feature.key.configValue, "FALSE", "g1")

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) {
            configService.upsertConfig(feature.key.configValue, "true", "g1")
        }
    }

    @Test
    fun `happy path defers edit and updates the message`() {
        every {
            fx.event.componentId
        } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:ACTIVITY_TRACKING"
        every {
            configService.getConfigByName(Configurations.ACTIVITY_TRACKING.configValue, "g1")
        } returns null

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.deferEdit() }
        verify(exactly = 1) { fx.hook.editOriginalEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            fx.editAction.setComponents(*anyVararg<MessageTopLevelComponent>())
        }
    }

    @Test
    fun `falls back to doneButtonRow when source message has no second row`() {
        // The default fixture leaves message.components as the empty
        // relaxed-mock return, exercising the InstallToggleButton's
        // `?: doneButtonRow()` fallback. We just verify no exception is
        // thrown and the edit still happens.
        every { fx.event.componentId } returns "${InstallWizard.BTN_TOGGLE_PREFIX}:ACTIVITY_TRACKING"
        every {
            configService.getConfigByName(Configurations.ACTIVITY_TRACKING.configValue, "g1")
        } returns null

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) {
            fx.editAction.setComponents(*anyVararg<MessageTopLevelComponent>())
        }
    }
}
