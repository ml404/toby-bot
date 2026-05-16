package database.economy

import kotlin.random.Random

/**
 * Pure-logic Plinko: an 8-row peg board with 9 landing buckets and three
 * risk profiles. No Spring, no DB, no JDA — just binomial drops over a
 * fair coin per row and a multiplier table lookup at the bottom.
 *
 * Mechanic
 *   Each row of pegs flips a fair coin: heads shifts the ball right, tails
 *   left. After [ROWS] independent draws the ball lands in bucket
 *   `k = right-count` (range `0..ROWS`). The bucket's multiplier in the
 *   chosen [Risk]'s payout table determines the payout (`multiplier × stake`).
 *
 * Risk profiles (all targeting ~0.90 RTP under the binomial bucket
 * distribution `P(k) = C(ROWS,k) / 2^ROWS`):
 *   - LOW    — narrow range, no busts, small refund-ish center buckets.
 *              Outer 1.9×, tapering to a 0.4× near-bust center.
 *   - MEDIUM — wider range, single bust at the center.
 *              Outer 12×, with 0.7× and 0× ring buckets.
 *   - HIGH   — top-heavy, multiple busts around the center.
 *              Outer 40×, three center 0× buckets.
 *
 * Each profile's RTP is the dot product of the binomial bucket probability
 * vector and its multiplier vector; pinned in [PlinkoTest] so a tuning
 * tweak that drifts RTP can't ship unnoticed.
 *
 * Stake bounds (`MIN_STAKE` / `MAX_STAKE`) live here so the Discord
 * command, the web controller, and the service all agree on what's valid.
 */
class Plinko(
    private val payouts: Map<Risk, DoubleArray> = DEFAULT_PAYOUTS
) {

    enum class Risk { LOW, MEDIUM, HIGH }

    data class Drop(val bucket: Int, val multiplier: Double, val risk: Risk) {
        val isWin: Boolean get() = multiplier > 1.0
        val isPush: Boolean get() = multiplier == 1.0
        val isLoss: Boolean get() = multiplier < 1.0
    }

    fun drop(risk: Risk, random: Random): Drop {
        var rights = 0
        repeat(ROWS) { if (random.nextBoolean()) rights += 1 }
        val table = payouts.getValue(risk)
        return Drop(bucket = rights, multiplier = table[rights], risk = risk)
    }

    fun payoutTable(risk: Risk): DoubleArray = payouts.getValue(risk).copyOf()

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        /** 8 peg rows → 9 buckets (indices 0..8). */
        const val ROWS: Int = 8
        const val BUCKETS: Int = ROWS + 1

        /**
         * Risk profile payout tables. Each is a length-[BUCKETS] vector of
         * (possibly fractional) multipliers; index `k` is the multiplier
         * for the bucket where the ball landed after `k` right-shifts.
         *
         * Targets: all three profiles RTP ≈ 0.89-0.90, pinned by
         * [database.economy.PlinkoTest].
         */
        val DEFAULT_PAYOUTS: Map<Risk, DoubleArray> = mapOf(
            // Σ p(k)·m[k] / 256 = 227.8/256 ≈ 0.890
            Risk.LOW to doubleArrayOf(1.9, 1.4, 1.1, 1.0, 0.4, 1.0, 1.1, 1.4, 1.9),
            // 228.8/256 ≈ 0.894
            Risk.MEDIUM to doubleArrayOf(12.0, 3.0, 1.4, 0.7, 0.0, 0.7, 1.4, 3.0, 12.0),
            // 228.0/256 ≈ 0.891
            Risk.HIGH to doubleArrayOf(40.0, 4.0, 1.5, 0.0, 0.0, 0.0, 1.5, 4.0, 40.0),
        )
    }
}
