package bot.toby.install.button

import bot.toby.install.InstallSentinel
import bot.toby.install.InstallWizard
import core.button.ButtonContext
import database.dto.UserDto
import database.service.ConfigService
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
    private val configService: ConfigService,
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_EXPRESS
    override val description: String = "Apply default settings and finish install."
    override fun ownerErrorMessage(): String = OWNER_ERROR_SETUP

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        InstallSentinel.writeIfFresh(configService, ctx.guild.id, mode = "express")
        event.hook.editOriginalEmbeds(InstallWizard.expressDoneEmbed())
            .setComponents()
            .queue()
    }
}

internal const val OWNER_ERROR_SETUP: String = "Only the server owner can run install setup."
