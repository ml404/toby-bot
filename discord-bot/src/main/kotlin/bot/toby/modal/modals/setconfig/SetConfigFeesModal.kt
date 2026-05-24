package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig fees` — the four "pool feed" percentages that route
 * stake/trade money into the per-guild jackpot pool. All four are
 * decimal percentages; ranges mirror the legacy command's validation.
 */
@Component
class SetConfigFeesModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Jackpot feed % rates"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.JACKPOT_LOSS_TRIBUTE_PCT to FieldSpec.IntRange("Loss tribute %", 0..50),
        Configurations.JACKPOT_WIN_PCT to FieldSpec.DoubleRange("Jackpot win %", 0.0..50.0),
        Configurations.TRADE_BUY_FEE_PCT to FieldSpec.DoubleRange("Coin BUY fee %", 0.0..25.0),
        Configurations.TRADE_SELL_FEE_PCT to FieldSpec.DoubleRange("Coin SELL fee %", 0.0..25.0),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.JACKPOT_LOSS_TRIBUTE_PCT to FIELD_LOSS_TRIBUTE_PCT,
        Configurations.JACKPOT_WIN_PCT to FIELD_WIN_PCT,
        Configurations.TRADE_BUY_FEE_PCT to FIELD_TRADE_BUY_FEE_PCT,
        Configurations.TRADE_SELL_FEE_PCT to FIELD_TRADE_SELL_FEE_PCT,
    )

    companion object {
        const val MODAL_NAME = "setconfig_fees"
        const val FIELD_LOSS_TRIBUTE_PCT = "loss_tribute_pct"
        const val FIELD_WIN_PCT = "win_pct"
        const val FIELD_TRADE_BUY_FEE_PCT = "trade_buy_fee_pct"
        const val FIELD_TRADE_SELL_FEE_PCT = "trade_sell_fee_pct"
    }
}
