package bot.toby.install.button

import bot.toby.install.InstallAuth
import bot.toby.install.InstallWizard
import bot.toby.install.OptInFeatures
import core.button.ButtonContext
import database.dto.user.UserDto
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * Flips one [OptInFeatures] config key between `"true"` and `"false"`.
 * One bean handles every toggle button — the colon-suffix on the
 * componentId names the [database.dto.guild.ConfigDto.Configurations] enum
 * entry being toggled (e.g. `install_toggle:ACTIVITY_TRACKING`).
 * Unknown suffixes reject ephemerally.
 *
 * The toggles view is only entered via Custom → Optional features, so
 * the bottom action row is always [InstallWizard.backButtonRow]; the
 * handler rebuilds it unconditionally rather than scraping the source
 * message's components.
 */
@Component
class InstallToggleButton(
    private val configService: ConfigService,
) : OwnerOnlyInstallButton() {

    override val name: String = InstallWizard.BTN_TOGGLE_PREFIX
    override val description: String = "Toggle an optional feature on or off."
    override fun ownerErrorMessage(): String = InstallAuth.TOGGLE_MESSAGE

    override fun handleAsOwner(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val suffix = event.componentId.substringAfter(':', missingDelimiterValue = "")
        val feature = OptInFeatures.byKeyName(suffix) ?: run {
            event.reply("Unknown feature toggle.").setEphemeral(true).queue()
            return
        }
        event.deferEdit().queue()
        val guildId = ctx.guild.id
        // Toggle is read-then-write — two rapid clicks could both read the
        // same "before" value and produce the same "after". In practice
        // Discord serializes interactions per-user-per-message, so this is
        // safe for a guild-owner-only button. If we ever extend toggles
        // beyond the owner, switch to a CAS-style upsert.
        val current = configService.getConfigByName(feature.key.configValue, guildId)?.value
        val newValue = if (current == "true") "false" else "true"
        configService.upsertConfig(feature.key.configValue, newValue, guildId)

        val reader = InstallWizard.configReader(configService, guildId)
        event.hook.editOriginalEmbeds(InstallWizard.togglesEmbed())
            .setComponents(InstallWizard.toggleRow(reader), InstallWizard.backButtonRow())
            .queue()
    }
}
