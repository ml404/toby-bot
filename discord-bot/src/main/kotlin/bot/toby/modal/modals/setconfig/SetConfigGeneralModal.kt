package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig general` — audio defaults + auto-delete delay + the two
 * channel knobs every guild cares about (default move target +
 * monthly leaderboard post location).
 */
@Component
class SetConfigGeneralModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "General settings"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.VOLUME to FieldSpec.IntRange("Default volume", 0..200),
        Configurations.INTRO_VOLUME to FieldSpec.IntRange("Default intro volume", 0..200),
        Configurations.DELETE_DELAY to FieldSpec.IntRange("Delete delay (seconds)", 0..600),
        Configurations.MOVE to FieldSpec.ChannelByIdStoreName("Default move channel"),
        Configurations.LEADERBOARD_CHANNEL to FieldSpec.ChannelByIdStoreId("Leaderboard channel"),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.VOLUME to FIELD_VOLUME,
        Configurations.INTRO_VOLUME to FIELD_INTRO_VOLUME,
        Configurations.DELETE_DELAY to FIELD_DELETE_DELAY,
        Configurations.MOVE to FIELD_MOVE,
        Configurations.LEADERBOARD_CHANNEL to FIELD_LEADERBOARD_CHANNEL,
    )

    companion object {
        const val MODAL_NAME = "setconfig_general"
        const val FIELD_VOLUME = "volume"
        const val FIELD_INTRO_VOLUME = "intro_volume"
        const val FIELD_DELETE_DELAY = "delete_delay"
        const val FIELD_MOVE = "move"
        const val FIELD_LEADERBOARD_CHANNEL = "leaderboard_channel"
    }
}
