package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig jackpot` — jackpot eligibility-gate tunables plus the
 * casino moderation log channel. `JACKPOT_WHEEL_SEGMENTS` stays
 * web-only (CSV format wants the dedicated editor in the
 * /moderation tab); the wheel-segments key is intentionally absent
 * from this modal.
 */
@Component
class SetConfigJackpotModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Jackpot tuning"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.JACKPOT_STAKE_ANCHOR to FieldSpec.LongMin("Stake anchor", 1L),
        Configurations.JACKPOT_WINNER_COOLDOWN_DAYS to FieldSpec.IntRange("Winner cooldown (days)", 0..365),
        Configurations.JACKPOT_RTP_MAX_PCT to FieldSpec.IntRange("RTP gate ceiling %", 0..100),
        Configurations.CASINO_MODLOG_CHANNEL_ID to FieldSpec.ChannelByIdStoreId("Casino modlog channel"),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.JACKPOT_STAKE_ANCHOR to FIELD_STAKE_ANCHOR,
        Configurations.JACKPOT_WINNER_COOLDOWN_DAYS to FIELD_WINNER_COOLDOWN_DAYS,
        Configurations.JACKPOT_RTP_MAX_PCT to FIELD_RTP_MAX_PCT,
        Configurations.CASINO_MODLOG_CHANNEL_ID to FIELD_CASINO_MODLOG_CHANNEL_ID,
    )

    companion object {
        const val MODAL_NAME = "setconfig_jackpot"
        const val FIELD_STAKE_ANCHOR = "stake_anchor"
        const val FIELD_WINNER_COOLDOWN_DAYS = "winner_cooldown_days"
        const val FIELD_RTP_MAX_PCT = "rtp_max_pct"
        const val FIELD_CASINO_MODLOG_CHANNEL_ID = "casino_modlog_channel_id"
    }
}
