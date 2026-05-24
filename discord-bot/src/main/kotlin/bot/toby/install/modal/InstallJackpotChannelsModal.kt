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
 * Wizard-only single-channel-picker modal for the Jackpot section's
 * casino modlog channel. Replaces typing a channel id into the bigger
 * `setconfig_jackpot` modal — owners pick the channel from a list.
 *
 * Channel-only modals stay single-component-type
 * ([EntitySelectMenu] in a [Label]) so Discord accepts the payload;
 * mixing `TextInput` + `EntitySelectMenu` in one modal triggers
 * client-side "Interaction failed".
 *
 * The picker uses `setRequiredRange(1, 1)`: Discord rejects a
 * `Label`-wrapped select menu with `min_values=0` (Label children are
 * implicitly required, and the API enforces this with
 * `COMPONENT_REQUIRED_ZERO_MIN_VALUES`). Owners must pick a channel
 * or cancel the modal (X / Esc) to back out without writing.
 *
 * The "no selection" defensive branch in [handle] is unreachable in
 * normal use but kept as a no-op fallback. If the picked channel has
 * been deleted between modal open and submit, the write is rejected
 * with no DB changes.
 */
@Component
class InstallJackpotChannelsModal(
    private val configService: ConfigService,
) : Modal {

    override val name: String = MODAL_NAME

    fun buildModal(): JdaModal {
        val menu = EntitySelectMenu.create(FIELD_MODLOG, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(ChannelType.TEXT)
            .setRequiredRange(1, 1)
            .build()
        return JdaModal.create(MODAL_NAME, "Quick jackpot channel setup")
            .addComponents(Label.of("Casino modlog channel", menu))
            .build()
    }

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guild = ctx.guild
        val guildId = guild.id

        val modlogChannelId = event.getValue(FIELD_MODLOG)?.asLongList?.firstOrNull()
        if (modlogChannelId == null) {
            event.reply("No channel picked — nothing changed.").setEphemeral(true).queue()
            return
        }
        val channel = guild.getTextChannelById(modlogChannelId)
        if (channel == null) {
            event.reply("Picked text channel no longer exists. No changes were written.")
                .setEphemeral(true).queue()
            return
        }
        configService.upsertAll(
            guildId,
            listOf(Configurations.CASINO_MODLOG_CHANNEL_ID.configValue to modlogChannelId.toString()),
        )
        event.reply("Saved:\n• Casino modlog channel → #${channel.name}")
            .setEphemeral(true).queue()
    }

    companion object {
        const val MODAL_NAME = "install_jackpot_channels"
        const val FIELD_MODLOG = "modlog_channel"
    }
}
