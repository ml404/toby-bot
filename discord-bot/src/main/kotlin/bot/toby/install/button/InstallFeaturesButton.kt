package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.UserDto
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * "Optional features" — accessible from the custom-setup root. Swaps the
 * section menu for the opt-in toggle row + a Back button. No DB writes
 * here; the toggle clicks write through [InstallToggleButton].
 */
@Component
class InstallFeaturesButton(
    private val configService: ConfigService,
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_FEATURES
    override val description: String = "Open the optional-features toggle view."

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        val reader = InstallWizard.configReader(configService, ctx.guild.id)
        event.hook.editOriginalEmbeds(InstallWizard.togglesEmbed())
            .setComponents(
                InstallWizard.toggleRow(reader),
                InstallWizard.backButtonRow(),
            )
            .queue()
    }
}
