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
 * "← Back" — restores the custom-setup root (section menu +
 * Optional-features button + Finish). Used from the section-detail menu,
 * the stakes-game sub-menu, and the toggles view. The target state is
 * the same in all three cases, so the handler is unconditional.
 */
@Component
class InstallBackButton(
    private val configService: ConfigService,
) : Button {

    override val name: String = InstallWizard.BTN_BACK
    override val description: String = "Return to the custom-install section menu."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (event.member?.isOwner != true) {
            event.reply("Only the server owner can navigate the install wizard.")
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
