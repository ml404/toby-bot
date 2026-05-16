package bot.toby.modal.modals.setconfig

import bot.toby.activity.ActivityTrackingNotifier
import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import database.service.ConfigService.UpsertResult
import org.springframework.stereotype.Component

/**
 * `/setconfig activity` — game-activity tracking toggle plus daily
 * credit knobs (UBI and the cap that applies to voice/command/intro
 * earnings).
 *
 * Preserves the first-enable side effect inherited from the old
 * monolithic `SetConfigCommand.setActivityTracking`: fires
 * [ActivityTrackingNotifier.notifyMembersOnFirstEnable] exactly once
 * when ACTIVITY_TRACKING transitions from disabled (or unset) to
 * `true`. Created and Updated-with-previous-`false` both qualify as
 * "previously disabled".
 */
@Component
class SetConfigActivityModal(
    configService: ConfigService,
    private val activityTrackingNotifier: ActivityTrackingNotifier,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Activity & UBI"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.ACTIVITY_TRACKING to FieldSpec.BoolStrict("Game-activity tracking"),
        Configurations.UBI_DAILY_AMOUNT to FieldSpec.IntRange("UBI daily amount (credits)", 0..1000),
        Configurations.DAILY_CREDIT_CAP to FieldSpec.IntRange("Daily credit cap", 0..10000),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.ACTIVITY_TRACKING to FIELD_ACTIVITY_TRACKING,
        Configurations.UBI_DAILY_AMOUNT to FIELD_UBI_DAILY_AMOUNT,
        Configurations.DAILY_CREDIT_CAP to FIELD_DAILY_CREDIT_CAP,
    )

    override fun afterWrites(
        ctx: ModalContext,
        results: List<Pair<Configurations, UpsertResult>>,
    ) {
        val activity = results.firstOrNull { it.first == Configurations.ACTIVITY_TRACKING } ?: return
        if (activity.second.dto.value != "true") return
        val previouslyEnabled = when (val r = activity.second) {
            is UpsertResult.Created -> false
            is UpsertResult.Updated -> r.previousValue?.equals("true", ignoreCase = true) == true
        }
        if (!previouslyEnabled) {
            activityTrackingNotifier.notifyMembersOnFirstEnable(ctx.guild)
        }
    }

    companion object {
        const val MODAL_NAME = "setconfig_activity"
        const val FIELD_ACTIVITY_TRACKING = "activity_tracking"
        const val FIELD_UBI_DAILY_AMOUNT = "ubi_daily_amount"
        const val FIELD_DAILY_CREDIT_CAP = "daily_credit_cap"
    }
}
