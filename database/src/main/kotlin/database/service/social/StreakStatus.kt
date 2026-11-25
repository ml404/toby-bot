package database.service.social

import java.time.LocalDate

/**
 * Lifecycle state of a user's daily streak, derived from `lastClaimDate`
 * relative to today (UTC). This is the single definition of when a streak
 * is alive; the claim path, the reminder query and the web views all
 * derive from it.
 *
 *   - CLAIMED  — already claimed today; safe.
 *   - AT_RISK  — alive (last claim was yesterday) but unclaimed today, so
 *                it breaks at the next UTC midnight unless claimed.
 *   - LAPSED   — last claim was 2+ days ago; the stored count is stale and
 *                the next claim restarts at 1.
 *   - NEW      — never claimed.
 */
enum class StreakStatus {
    CLAIMED, AT_RISK, LAPSED, NEW;

    /** True while the next claim would extend the streak rather than restart it. */
    val isActive: Boolean get() = this == CLAIMED || this == AT_RISK

    companion object {
        fun of(lastClaimDate: LocalDate?, today: LocalDate): StreakStatus = when (lastClaimDate) {
            null -> NEW
            today -> CLAIMED
            atRiskClaimDate(today) -> AT_RISK
            else -> LAPSED
        }

        /** The only `lastClaimDate` that leaves a streak alive but unclaimed on [today]. */
        fun atRiskClaimDate(today: LocalDate): LocalDate = today.minusDays(1)
    }
}
