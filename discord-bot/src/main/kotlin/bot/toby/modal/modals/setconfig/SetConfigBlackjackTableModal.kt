package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component
import common.casino.blackjack.Blackjack

/**
 * `/setconfig blackjack_table` — seat count + the natural-blackjack
 * payout ratio numerator/denominator (e.g. 3:2 → num=3, den=2). Game
 * rules live in the paired `/setconfig blackjack_rules` modal.
 */
@Component
class SetConfigBlackjackTableModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Blackjack table shape"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.BLACKJACK_MAX_SEATS to FieldSpec.IntRange("Max seats", 2..7),
        Configurations.BLACKJACK_BJ_PAYOUT_NUM to FieldSpec.IntRange("BJ payout numerator", 1..10),
        Configurations.BLACKJACK_BJ_PAYOUT_DEN to FieldSpec.IntRange("BJ payout denominator", 1..10),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.BLACKJACK_MAX_SEATS to FIELD_MAX_SEATS,
        Configurations.BLACKJACK_BJ_PAYOUT_NUM to FIELD_BJ_PAYOUT_NUM,
        Configurations.BLACKJACK_BJ_PAYOUT_DEN to FIELD_BJ_PAYOUT_DEN,
    )

    companion object {
        const val MODAL_NAME = "setconfig_blackjack_table"
        const val FIELD_MAX_SEATS = "max_seats"
        const val FIELD_BJ_PAYOUT_NUM = "bj_payout_num"
        const val FIELD_BJ_PAYOUT_DEN = "bj_payout_den"
    }
}
