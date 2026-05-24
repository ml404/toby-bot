package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig poker_stakes` — money rules for new poker tables:
 * blinds, fixed-limit bet sizes, and rake. Buy-in bounds and seat
 * count live in `/setconfig poker_table` so each modal stays at or
 * under Discord's 5-component cap.
 */
@Component
class SetConfigPokerStakesModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Poker stakes"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.POKER_SMALL_BLIND to FieldSpec.LongMin("Small blind", 1L),
        Configurations.POKER_BIG_BLIND to FieldSpec.LongMin("Big blind", 1L),
        Configurations.POKER_SMALL_BET to FieldSpec.LongMin("Small bet", 1L),
        Configurations.POKER_BIG_BET to FieldSpec.LongMin("Big bet", 1L),
        Configurations.POKER_RAKE_PCT to FieldSpec.IntRange("Rake %", 0..20),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.POKER_SMALL_BLIND to FIELD_SMALL_BLIND,
        Configurations.POKER_BIG_BLIND to FIELD_BIG_BLIND,
        Configurations.POKER_SMALL_BET to FIELD_SMALL_BET,
        Configurations.POKER_BIG_BET to FIELD_BIG_BET,
        Configurations.POKER_RAKE_PCT to FIELD_RAKE_PCT,
    )

    companion object {
        const val MODAL_NAME = "setconfig_poker_stakes"
        const val FIELD_SMALL_BLIND = "small_blind"
        const val FIELD_BIG_BLIND = "big_blind"
        const val FIELD_SMALL_BET = "small_bet"
        const val FIELD_BIG_BET = "big_bet"
        const val FIELD_RAKE_PCT = "rake_pct"
    }
}
