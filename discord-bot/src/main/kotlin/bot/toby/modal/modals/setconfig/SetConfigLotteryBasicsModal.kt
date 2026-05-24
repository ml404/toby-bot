package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig lottery_basics` — daily-lottery on/off + ticket
 * economics + draw mode + announcement ping. Pool-revenue split
 * lives in the paired `/setconfig lottery_pools` modal.
 */
@Component
class SetConfigLotteryBasicsModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Daily lottery basics"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.LOTTERY_DAILY_ENABLED to FieldSpec.BoolStrict("Daily lottery enabled"),
        Configurations.LOTTERY_DAILY_TICKET_PRICE to FieldSpec.LongMin("Ticket price (credits)", 1L),
        Configurations.LOTTERY_DAILY_MIN_BUYERS to FieldSpec.IntRange("Min distinct buyers", 1..50),
        Configurations.LOTTERY_DAILY_MODE to FieldSpec.EnumChoice(
            "Draw mode", setOf("NUMBER_MATCH", "WEIGHTED"),
        ),
        Configurations.LOTTERY_PING_MODE to FieldSpec.EnumChoice(
            "Announcement ping", setOf("OFF", "HERE", "EVERYONE"),
        ),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.LOTTERY_DAILY_ENABLED to FIELD_ENABLED,
        Configurations.LOTTERY_DAILY_TICKET_PRICE to FIELD_TICKET_PRICE,
        Configurations.LOTTERY_DAILY_MIN_BUYERS to FIELD_MIN_BUYERS,
        Configurations.LOTTERY_DAILY_MODE to FIELD_MODE,
        Configurations.LOTTERY_PING_MODE to FIELD_PING_MODE,
    )

    companion object {
        const val MODAL_NAME = "setconfig_lottery_basics"
        const val FIELD_ENABLED = "enabled"
        const val FIELD_TICKET_PRICE = "ticket_price"
        const val FIELD_MIN_BUYERS = "min_buyers"
        const val FIELD_MODE = "mode"
        const val FIELD_PING_MODE = "ping_mode"
    }
}
