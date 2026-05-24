package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig blackjack_rules` — the hand-rules knobs admins tweak
 * most: rake, ante bounds, dealer-soft-17 behaviour, and the
 * per-actor shot clock. Seat count and blackjack-natural payout
 * ratio live in `/setconfig blackjack_table`.
 */
@Component
class SetConfigBlackjackRulesModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Blackjack rules"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.BLACKJACK_RAKE_PCT to FieldSpec.IntRange("Rake %", 0..20),
        Configurations.BLACKJACK_MIN_ANTE to FieldSpec.LongMin("Min ante", 1L),
        Configurations.BLACKJACK_MAX_ANTE to FieldSpec.LongMin("Max ante", 1L),
        Configurations.BLACKJACK_DEALER_HITS_SOFT_17 to FieldSpec.BoolStrict("Dealer hits soft 17"),
        Configurations.BLACKJACK_SHOT_CLOCK_SECONDS to FieldSpec.IntRange("Shot clock (seconds)", 0..600),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.BLACKJACK_RAKE_PCT to FIELD_RAKE_PCT,
        Configurations.BLACKJACK_MIN_ANTE to FIELD_MIN_ANTE,
        Configurations.BLACKJACK_MAX_ANTE to FIELD_MAX_ANTE,
        Configurations.BLACKJACK_DEALER_HITS_SOFT_17 to FIELD_DEALER_HITS_SOFT_17,
        Configurations.BLACKJACK_SHOT_CLOCK_SECONDS to FIELD_SHOT_CLOCK_SECONDS,
    )

    companion object {
        const val MODAL_NAME = "setconfig_blackjack_rules"
        const val FIELD_RAKE_PCT = "rake_pct"
        const val FIELD_MIN_ANTE = "min_ante"
        const val FIELD_MAX_ANTE = "max_ante"
        const val FIELD_DEALER_HITS_SOFT_17 = "dealer_hits_soft_17"
        const val FIELD_SHOT_CLOCK_SECONDS = "shot_clock_seconds"
    }
}
