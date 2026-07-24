package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig lottery_engagement` — low-engagement levers for the
 * daily lottery: the rollover pot, participation-streak bonus, and the
 * NUMBER_MATCH draw shape (pick count / number range). Pairs with
 * `/setconfig lottery_basics` and `/setconfig lottery_pools`.
 */
@Component
class SetConfigLotteryEngagementModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Lottery engagement & odds"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.LOTTERY_DAILY_ROLLOVER_ENABLED to FieldSpec.BoolStrict("Rollover pot enabled"),
        Configurations.LOTTERY_STREAK_DAYS to FieldSpec.IntRange("Streak threshold (days, 0=off)", 0..365),
        Configurations.LOTTERY_STREAK_BONUS to FieldSpec.IntRange("Streak bonus (credits/day)", 0..1_000_000),
        Configurations.LOTTERY_DAILY_PICK_COUNT to FieldSpec.IntRange("Match picks (2-6)", 2..6),
        Configurations.LOTTERY_DAILY_NUMBER_MAX to FieldSpec.IntRange("Match number range (10-99)", 10..99),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.LOTTERY_DAILY_ROLLOVER_ENABLED to FIELD_ROLLOVER,
        Configurations.LOTTERY_STREAK_DAYS to FIELD_STREAK_DAYS,
        Configurations.LOTTERY_STREAK_BONUS to FIELD_STREAK_BONUS,
        Configurations.LOTTERY_DAILY_PICK_COUNT to FIELD_PICK_COUNT,
        Configurations.LOTTERY_DAILY_NUMBER_MAX to FIELD_NUMBER_MAX,
    )

    companion object {
        const val MODAL_NAME = "setconfig_lottery_engagement"
        const val FIELD_ROLLOVER = "rollover"
        const val FIELD_STREAK_DAYS = "streak_days"
        const val FIELD_STREAK_BONUS = "streak_bonus"
        const val FIELD_PICK_COUNT = "pick_count"
        const val FIELD_NUMBER_MAX = "number_max"
    }
}
