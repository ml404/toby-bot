package bot.toby.install.button

import bot.toby.install.InstallAuth
import bot.toby.install.InstallCompletionService
import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.user.UserDto
import org.springframework.stereotype.Component

/**
 * "Express setup" — records the install sentinel
 * (`INSTALL_MODE=express` + `INSTALLED_AT=<now>`) and strips the wizard
 * components in one shot. No follow-up step.
 *
 * If `INSTALL_MODE` is already set (the owner re-ran `/install` after a
 * previous install), the sentinel is NOT downgraded — we still show the
 * done embed but skip the writes so the original install record stays
 * intact.
 */
@Component
class InstallExpressButton(
    private val installCompletionService: InstallCompletionService,
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_EXPRESS
    override val description: String = "Apply default settings and finish install."
    override fun ownerErrorMessage(): String = InstallAuth.SETUP_MESSAGE

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        installCompletionService.complete(ctx.guild, mode = "express", channelId = event.channel.idLong)
        event.hook.editOriginalEmbeds(InstallWizard.expressDoneEmbed())
            .setComponents()
            .queue()
    }
}
