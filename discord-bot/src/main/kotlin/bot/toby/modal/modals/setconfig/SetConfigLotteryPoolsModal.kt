package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig lottery_pools` — pool-seed and revenue-split percentages
 * plus the announce channel override. Pairs with
 * `/setconfig lottery_basics` (toggle + ticket economics).
 */
@Component
class SetConfigLotteryPoolsModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Lottery pool routing"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.LOTTERY_DAILY_SEED_PCT to FieldSpec.IntRange("Pool seed %", 1..100),
        Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT to FieldSpec.IntRange("Ticket revenue → jackpot %", 0..100),
        Configurations.LOTTERY_CHANNEL to FieldSpec.ChannelByIdStoreId("Announcement channel"),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.LOTTERY_DAILY_SEED_PCT to FIELD_SEED_PCT,
        Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT to FIELD_REVENUE_JACKPOT_PCT,
        Configurations.LOTTERY_CHANNEL to FIELD_CHANNEL,
    )

    companion object {
        const val MODAL_NAME = "setconfig_lottery_pools"
        const val FIELD_SEED_PCT = "seed_pct"
        const val FIELD_REVENUE_JACKPOT_PCT = "revenue_jackpot_pct"
        const val FIELD_CHANNEL = "channel"
    }
}
