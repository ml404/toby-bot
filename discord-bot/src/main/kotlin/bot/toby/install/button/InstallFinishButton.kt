package bot.toby.install.button

import bot.toby.install.InstallAuth
import bot.toby.install.InstallCompletionService
import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.user.UserDto
import org.springframework.stereotype.Component

/**
 * "Finish" — records the install sentinel (`INSTALL_MODE=custom` +
 * `INSTALLED_AT=<now>`) and strips the wizard components. Used on the
 * custom-setup path.
 *
 * If `INSTALL_MODE` is already set, the sentinel is not rewritten —
 * the wizard doesn't downgrade an existing install on a second pass.
 */
@Component
class InstallFinishButton(
    private val installCompletionService: InstallCompletionService,
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_FINISH
    override val description: String = "Finish the custom install."
    override fun ownerErrorMessage(): String = InstallAuth.SETUP_MESSAGE

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        installCompletionService.complete(ctx.guild, mode = "custom", channelId = event.channel.idLong)
        event.hook.editOriginalEmbeds(InstallWizard.finishDoneEmbed())
            .setComponents()
            .queue()
    }
}
