package web.view

/**
 * Resolved view of the participation-incentive config used by both
 * the moderation page (`overview.lotteryIncentives`) and the player
 * lottery page (`snapshot.weighted.incentives`). Carrying only the
 * active tiers — anything with a zero threshold is filtered out by
 * `database.service.lottery.LotteryHelper` — so the surfaces never have to
 * branch on "is this tier configured" themselves.
 */
data class LotteryIncentivesView(
    val bulkTiers: List<BulkBonusTierView>,
    val multiplierTiers: List<MultiplierTierView>,
    val poolMilestones: List<PoolMilestoneView>,
) {
    val isEmpty: Boolean
        get() = bulkTiers.isEmpty() && multiplierTiers.isEmpty() && poolMilestones.isEmpty()

    companion object {
        fun empty(): LotteryIncentivesView =
            LotteryIncentivesView(emptyList(), emptyList(), emptyList())
    }
}

data class BulkBonusTierView(val buy: Long, val bonus: Long)
data class MultiplierTierView(val total: Long, val bp: Int)
data class PoolMilestoneView(val tickets: Long, val pct: Long)
