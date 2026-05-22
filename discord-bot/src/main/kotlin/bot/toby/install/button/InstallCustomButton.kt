package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.UserDto
import database.service.ConfigService
import org.springframework.stereotype.Component

/**
 * "Custom setup" — swaps the welcome embed + buttons for the section
 * menu + the Optional-features and Finish buttons. No DB writes happen
 * here; the install sentinel is recorded only when the owner clicks
 * Finish.
 */
@Component
class InstallCustomButton(
    private val configService: ConfigService,
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_CUSTOM
    override val description: String = "Open the custom-install section menu."
    override fun ownerErrorMessage(): String = OWNER_ERROR_SETUP

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        val reader = InstallWizard.configReader(configService, ctx.guild.id)
        event.hook.editOriginalEmbeds(InstallWizard.customSectionEmbed())
            .setComponents(*InstallWizard.customRootRows(reader))
            .queue()
    }
}
