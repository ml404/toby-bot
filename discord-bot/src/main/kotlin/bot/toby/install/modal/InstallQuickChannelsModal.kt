package bot.toby.install.modal

import core.modal.Modal
import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.entities.channel.ChannelType
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.modals.Modal as JdaModal

/**
 * Wizard-only modal that uses Discord's channel-picker
 * [EntitySelectMenu] components instead of typed channel IDs/names. Two
 * pickers in one shot — voice channel for the default move target, text
 * channel for the leaderboard. Owners click their channels from a list;
 * no looking up IDs.
 *
 * Discord modals support select menus inside `Label` since the late
 * 2024 components-v2 API; JDA 6.3 exposes this via `Label.of(name, menu)`.
 *
 * On submit:
 * - The voice channel selection's *name* is written to
 *   `DEFAULT_MOVE_CHANNEL` (matches the existing
 *   `SetConfigFieldValidator.FieldSpec.ChannelByIdStoreName` convention).
 * - The text channel selection's *id* is written to
 *   `LEADERBOARD_CHANNEL` (matches `ChannelByIdStoreId`).
 *
 * Either field can be left empty (no selection); empty selections skip
 * the write.
 */
@Component
class InstallQuickChannelsModal(
    private val configService: ConfigService,
) : Modal {

    override val name: String = MODAL_NAME

    fun buildModal(): JdaModal {
        val moveMenu = EntitySelectMenu.create(FIELD_MOVE, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(ChannelType.VOICE)
            .setRequiredRange(0, 1)
            .build()
        val leaderboardMenu = EntitySelectMenu.create(FIELD_LEADERBOARD, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(ChannelType.TEXT)
            .setRequiredRange(0, 1)
            .build()
        return JdaModal.create(MODAL_NAME, "Quick channel setup")
            .addComponents(
                Label.of("Default move (voice) channel", moveMenu),
                Label.of("Leaderboard (text) channel", leaderboardMenu),
            )
            .build()
    }

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guild = ctx.guild
        val guildId = guild.id

        val written = mutableListOf<String>()

        val moveChannelId = event.getValue(FIELD_MOVE)?.asLongList?.firstOrNull()
        if (moveChannelId != null) {
            val channel = guild.getVoiceChannelById(moveChannelId)
            if (channel == null) {
                event.reply("Picked voice channel no longer exists. No changes were written.")
                    .setEphemeral(true).queue()
                return
            }
            configService.upsertConfig(Configurations.MOVE.configValue, channel.name, guildId)
            written += "Move channel → #${channel.name}"
        }

        val leaderboardChannelId = event.getValue(FIELD_LEADERBOARD)?.asLongList?.firstOrNull()
        if (leaderboardChannelId != null) {
            val channel = guild.getTextChannelById(leaderboardChannelId)
            if (channel == null) {
                event.reply("Picked text channel no longer exists. No changes were written.")
                    .setEphemeral(true).queue()
                return
            }
            configService.upsertConfig(
                Configurations.LEADERBOARD_CHANNEL.configValue,
                leaderboardChannelId.toString(),
                guildId,
            )
            written += "Leaderboard channel → #${channel.name}"
        }

        if (written.isEmpty()) {
            event.reply("No channels picked — nothing changed.").setEphemeral(true).queue()
        } else {
            event.reply("Saved:\n" + written.joinToString("\n") { "• $it" })
                .setEphemeral(true).queue()
        }
    }

    companion object {
        const val MODAL_NAME = "install_quick_channels"
        const val FIELD_MOVE = "move_channel"
        const val FIELD_LEADERBOARD = "leaderboard_channel"
    }
}
