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
 * Wizard-only single-channel-picker modal for the Lottery section's
 * announce channel. Mirrors [InstallJackpotChannelsModal] — owners
 * pick the channel from a list instead of typing the id into the
 * bigger `setconfig_lottery_pools` modal.
 *
 * See [InstallJackpotChannelsModal] for the design constraint that
 * keeps these channel-only modals single-component-type and for the
 * `setRequiredRange(1, 1)` requirement Discord imposes on
 * `Label`-wrapped select menus.
 */
@Component
class InstallLotteryChannelsModal(
    private val configService: ConfigService,
) : Modal {

    override val name: String = MODAL_NAME

    fun buildModal(): JdaModal {
        val menu = EntitySelectMenu.create(FIELD_ANNOUNCE, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(ChannelType.TEXT)
            .setRequiredRange(1, 1)
            .build()
        return JdaModal.create(MODAL_NAME, "Quick lottery channel setup")
            .addComponents(Label.of("Lottery announce channel", menu))
            .build()
    }

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guild = ctx.guild
        val guildId = guild.id

        val announceChannelId = event.getValue(FIELD_ANNOUNCE)?.asLongList?.firstOrNull()
        if (announceChannelId == null) {
            event.reply("No channel picked — nothing changed.").setEphemeral(true).queue()
            return
        }
        val channel = guild.getTextChannelById(announceChannelId)
        if (channel == null) {
            event.reply("Picked text channel no longer exists. No changes were written.")
                .setEphemeral(true).queue()
            return
        }
        configService.upsertAll(
            guildId,
            listOf(Configurations.LOTTERY_CHANNEL.configValue to announceChannelId.toString()),
        )
        event.reply("Saved:\n• Lottery announce channel → #${channel.name}")
            .setEphemeral(true).queue()
    }

    companion object {
        const val MODAL_NAME = "install_lottery_channels"
        const val FIELD_ANNOUNCE = "announce_channel"
    }
}
