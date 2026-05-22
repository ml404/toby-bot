package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.Button
import core.button.ButtonContext
import database.dto.ConfigDto.Configurations
import database.dto.UserDto
import database.service.ConfigService
import org.springframework.stereotype.Component

/**
 * "Optional features" — accessible from the custom-setup root. Swaps the
 * section menu for the opt-in toggle row + a Back button. No DB writes
 * here; the toggle clicks write through [InstallToggleButton].
 */
@Component
class InstallFeaturesButton(
    private val configService: ConfigService,
) : Button {

    override val name: String = InstallWizard.BTN_FEATURES
    override val description: String = "Open the optional-features toggle view."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (event.member?.isOwner != true) {
            event.reply("Only the server owner can use the install wizard.")
                .setEphemeral(true).queue()
            return
        }
        event.deferEdit().queue()
        val guildId = ctx.guild.id
        val reader: (Configurations) -> String? =
            { key -> configService.getConfigByName(key.configValue, guildId)?.value }
        event.hook.editOriginalEmbeds(InstallWizard.togglesEmbed())
            .setComponents(
                InstallWizard.toggleRow(reader),
                InstallWizard.backButtonRow(),
            )
            .queue()
    }
}
