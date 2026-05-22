package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.UserDto
import database.service.ConfigService
import org.springframework.stereotype.Component

/**
 * "← Back" — restores the custom-setup root (section menu +
 * Optional-features button + Finish). Used from the section-detail menu,
 * the stakes-game sub-menu, and the toggles view. The target state is
 * the same in all three cases, so the handler is unconditional.
 */
@Component
class InstallBackButton(
    private val configService: ConfigService,
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_BACK
    override val description: String = "Return to the custom-install section menu."
    override fun ownerErrorMessage(): String = "Only the server owner can navigate the install wizard."

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        val reader = InstallWizard.configReader(configService, ctx.guild.id)
        event.hook.editOriginalEmbeds(InstallWizard.customSectionEmbed())
            .setComponents(*InstallWizard.customRootRows(reader))
            .queue()
    }
}
