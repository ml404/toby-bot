package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.Button
import core.button.ButtonContext
import database.dto.ConfigDto.Configurations
import database.dto.UserDto
import database.service.ConfigService
import org.springframework.stereotype.Component

/**
 * "Finish" — records the install sentinel (`INSTALL_MODE=custom` +
 * `INSTALLED_AT=<now>`) and strips the wizard components. Used on the
 * custom-setup path.
 */
@Component
class InstallFinishButton(
    private val configService: ConfigService,
) : Button {

    override val name: String = InstallWizard.BTN_FINISH
    override val description: String = "Finish the custom install."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (event.member?.isOwner != true) {
            event.reply("Only the server owner can finish install setup.")
                .setEphemeral(true).queue()
            return
        }
        event.deferEdit().queue()
        val guildId = ctx.guild.id
        configService.upsertConfig(Configurations.INSTALL_MODE.configValue, "custom", guildId)
        configService.upsertConfig(
            Configurations.INSTALLED_AT.configValue,
            System.currentTimeMillis().toString(),
            guildId,
        )
        event.hook.editOriginalEmbeds(InstallWizard.finishDoneEmbed())
            .setComponents()
            .queue()
    }
}
