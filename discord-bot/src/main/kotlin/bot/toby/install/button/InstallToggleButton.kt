package bot.toby.install.button

import bot.toby.install.InstallWizard
import bot.toby.install.OptInFeatures
import core.button.Button
import core.button.ButtonContext
import database.dto.ConfigDto.Configurations
import database.dto.UserDto
import database.service.ConfigService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import org.springframework.stereotype.Component

/**
 * Flips one [OptInFeatures] config key between `"true"` and `"false"`.
 * One bean handles every toggle button — the colon-suffix on the
 * componentId names the [Configurations] enum entry being toggled
 * (e.g. `install_toggle:ACTIVITY_TRACKING`). Unknown suffixes reject
 * ephemerally.
 *
 * After the flip, the toggle action row is rebuilt with current DB
 * state; the bottom row of the message (Done from express, Back from
 * custom) is preserved verbatim so the wizard's navigation context
 * stays intact without encoding it in the componentId.
 */
@Component
class InstallToggleButton(
    private val configService: ConfigService,
) : Button {

    override val name: String = InstallWizard.BTN_TOGGLE_PREFIX
    override val description: String = "Toggle an optional feature on or off."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (event.member?.isOwner != true) {
            event.reply("Only the server owner can toggle install settings.")
                .setEphemeral(true).queue()
            return
        }
        val suffix = event.componentId.substringAfter(':', missingDelimiterValue = "")
        val feature = OptInFeatures.byKeyName(suffix) ?: run {
            event.reply("Unknown feature toggle.").setEphemeral(true).queue()
            return
        }
        event.deferEdit().queue()
        val guildId = ctx.guild.id
        val current = configService.getConfigByName(feature.key.configValue, guildId)?.value
        val newValue = if (current == "true") "false" else "true"
        configService.upsertConfig(feature.key.configValue, newValue, guildId)

        val reader: (Configurations) -> String? =
            { key -> configService.getConfigByName(key.configValue, guildId)?.value }
        val rebuiltToggleRow = InstallWizard.toggleRow(reader)

        // Preserve whatever bottom row was already on the message;
        // toggles are now only entered from Custom → Optional features
        // (which uses the Back button), but we don't assume it — anything
        // already in the second row stays.
        val existingRows = event.message.components.filterIsInstance<ActionRow>()
        val bottomRow = existingRows.drop(1).firstOrNull() ?: InstallWizard.backButtonRow()

        event.hook.editOriginalEmbeds(InstallWizard.togglesEmbed())
            .setComponents(rebuiltToggleRow, bottomRow)
            .queue()
    }
}
