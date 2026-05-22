package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.Button
import core.button.ButtonContext
import database.dto.ConfigDto.Configurations
import database.dto.UserDto
import database.service.ConfigService
import org.springframework.stereotype.Component

/**
 * "Express setup" — records the install sentinel (`INSTALL_MODE=express` +
 * `INSTALLED_AT=<now>`) and shows the optional-features toggle row so the
 * owner can flip on activity tracking / daily lottery without leaving the
 * wizard. A "Done" button on the same row ends the express path.
 */
@Component
class InstallExpressButton(
    private val configService: ConfigService,
) : Button {

    override val name: String = InstallWizard.BTN_EXPRESS
    override val description: String = "Apply default settings and finish install."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (event.member?.isOwner != true) {
            event.reply("Only the server owner can run install setup.")
                .setEphemeral(true).queue()
            return
        }
        event.deferEdit().queue()
        val guildId = ctx.guild.id
        configService.upsertConfig(Configurations.INSTALL_MODE.configValue, "express", guildId)
        configService.upsertConfig(
            Configurations.INSTALLED_AT.configValue,
            System.currentTimeMillis().toString(),
            guildId,
        )
        // One-click Express: write sentinel, show the final done embed,
        // strip components. Owners who want to opt in to lottery /
        // activity tracking can use /install → Custom → Optional features
        // (or /setconfig directly).
        event.hook.editOriginalEmbeds(InstallWizard.expressDoneEmbed())
            .setComponents()
            .queue()
    }
}
