package bot.toby.install.button

import bot.toby.install.InstallWizard
import core.button.Button
import core.button.ButtonContext
import database.dto.ConfigDto.Configurations
import database.dto.UserDto
import database.service.ConfigService
import net.dv8tion.jda.api.components.actionrow.ActionRow
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
) : Button {

    override val name: String = InstallWizard.BTN_CUSTOM
    override val description: String = "Open the custom-install section menu."
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
        val reader: (Configurations) -> String? =
            { key -> configService.getConfigByName(key.configValue, guildId)?.value }
        event.hook.editOriginalEmbeds(InstallWizard.customSectionEmbed())
            .setComponents(
                ActionRow.of(InstallWizard.sectionMenu(reader)),
                InstallWizard.customRootBottomRow(),
            )
            .queue()
    }
}
