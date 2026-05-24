package bot.toby.install.modal

import core.modal.Modal
import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.entities.channel.ChannelType
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.modals.Modal as JdaModal

/**
 * Wizard-only modal that surfaces the Activity section's two
 * announcement channels — `LEVEL_UP_CHANNEL` and
 * `ACHIEVEMENT_ANNOUNCE_CHANNEL` — as native channel pickers. Neither
 * key has a `setconfig` field today, so this modal is the only way to
 * set them without SQL.
 *
 * Both channels are optional: when unset, the consuming services fall
 * back gracefully (level-up: message/system channel; achievement:
 * silent). To clear an override, an owner edits via SQL — the picker
 * UX is for the 99% case of "set it to this channel".
 *
 * See [InstallJackpotChannelsModal] for the design constraint that
 * keeps these channel-only modals single-component-type, and for the
 * `setRequiredRange(1, 1)` requirement Discord imposes on
 * `Label`-wrapped select menus.
 */
@Component
class InstallActivityChannelsModal(
    private val configService: ConfigService,
) : Modal {

    override val name: String = MODAL_NAME

    fun buildModal(): JdaModal {
        val levelUpMenu = EntitySelectMenu.create(FIELD_LEVEL_UP, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(ChannelType.TEXT)
            .setRequiredRange(1, 1)
            .build()
        val achievementMenu = EntitySelectMenu.create(FIELD_ACHIEVEMENT, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(ChannelType.TEXT)
            .setRequiredRange(1, 1)
            .build()
        return JdaModal.create(MODAL_NAME, "Quick activity channel setup")
            .addComponents(
                Label.of("Level-up announce channel", levelUpMenu),
                Label.of("Achievement announce channel", achievementMenu),
            )
            .build()
    }

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guild = ctx.guild
        val guildId = guild.id

        val rows = mutableListOf<Pair<String, String>>()
        val written = mutableListOf<String>()

        val levelUpId = event.getValue(FIELD_LEVEL_UP)?.asLongList?.firstOrNull()
        if (levelUpId != null) {
            val channel = guild.getTextChannelById(levelUpId)
            if (channel == null) {
                event.reply("Picked level-up channel no longer exists. No changes were written.")
                    .setEphemeral(true).queue()
                return
            }
            rows += Configurations.LEVEL_UP_CHANNEL.configValue to levelUpId.toString()
            written += "Level-up channel → #${channel.name}"
        }

        val achievementId = event.getValue(FIELD_ACHIEVEMENT)?.asLongList?.firstOrNull()
        if (achievementId != null) {
            val channel = guild.getTextChannelById(achievementId)
            if (channel == null) {
                event.reply("Picked achievement channel no longer exists. No changes were written.")
                    .setEphemeral(true).queue()
                return
            }
            rows += Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL.configValue to achievementId.toString()
            written += "Achievement channel → #${channel.name}"
        }

        if (rows.isEmpty()) {
            event.reply("No channels picked — nothing changed.").setEphemeral(true).queue()
            return
        }
        configService.upsertAll(guildId, rows)
        event.reply("Saved:\n" + written.joinToString("\n") { "• $it" })
            .setEphemeral(true).queue()
    }

    companion object {
        const val MODAL_NAME = "install_activity_channels"
        const val FIELD_LEVEL_UP = "level_up_channel"
        const val FIELD_ACHIEVEMENT = "achievement_channel"
    }
}
