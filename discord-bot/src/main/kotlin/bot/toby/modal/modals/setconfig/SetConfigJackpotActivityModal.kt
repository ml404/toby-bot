package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig jackpot_activity` — the two "user must be active to win
 * the jackpot" knobs. Both default to a permissive 0/1 (gate disabled)
 * so the typical guild doesn't notice them; raise both to enforce a
 * "must have voice-credit activity on N of the last M days" gate.
 */
@Component
class SetConfigJackpotActivityModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Jackpot activity gate"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS to FieldSpec.IntRange("Activity window (days)", 0..365),
        Configurations.JACKPOT_ACTIVITY_MIN_DAYS to FieldSpec.IntRange("Min active days in window", 1..365),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS to FIELD_WINDOW_DAYS,
        Configurations.JACKPOT_ACTIVITY_MIN_DAYS to FIELD_MIN_DAYS,
    )

    companion object {
        const val MODAL_NAME = "setconfig_jackpot_activity"
        const val FIELD_WINDOW_DAYS = "window_days"
        const val FIELD_MIN_DAYS = "min_days"
    }
}
