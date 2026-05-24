package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig poker_table` — table-shape knobs: buy-in bounds, seat
 * count, and the per-actor shot clock. Money rules live in the
 * paired `/setconfig poker_stakes` modal.
 */
@Component
class SetConfigPokerTableModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Poker table shape"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.POKER_MIN_BUY_IN to FieldSpec.LongMin("Min buy-in", 1L),
        Configurations.POKER_MAX_BUY_IN to FieldSpec.LongMin("Max buy-in", 1L),
        Configurations.POKER_MAX_SEATS to FieldSpec.IntRange("Max seats", 2..9),
        Configurations.POKER_SHOT_CLOCK_SECONDS to FieldSpec.IntRange("Shot clock (seconds)", 0..600),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.POKER_MIN_BUY_IN to FIELD_MIN_BUY_IN,
        Configurations.POKER_MAX_BUY_IN to FIELD_MAX_BUY_IN,
        Configurations.POKER_MAX_SEATS to FIELD_MAX_SEATS,
        Configurations.POKER_SHOT_CLOCK_SECONDS to FIELD_SHOT_CLOCK_SECONDS,
    )

    companion object {
        const val MODAL_NAME = "setconfig_poker_table"
        const val FIELD_MIN_BUY_IN = "min_buy_in"
        const val FIELD_MAX_BUY_IN = "max_buy_in"
        const val FIELD_MAX_SEATS = "max_seats"
        const val FIELD_SHOT_CLOCK_SECONDS = "shot_clock_seconds"
    }
}
