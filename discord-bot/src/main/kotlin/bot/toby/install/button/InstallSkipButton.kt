package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.UserDto
import org.springframework.stereotype.Component

/**
 * "Skip for now" — dismisses the wizard with no DB writes. The install
 * sentinel is intentionally not set, so a future re-invite will re-post
 * the welcome message (skip is non-terminal).
 */
@Component
class InstallSkipButton : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_SKIP
    override val description: String = "Dismiss the install wizard without making changes."
    override fun ownerErrorMessage(): String = "Only the server owner can dismiss the install prompt."

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        event.hook.editOriginalEmbeds(InstallWizard.skipDismissedEmbed())
            .setComponents()
            .queue()
    }
}
