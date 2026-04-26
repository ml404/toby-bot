package database.economy

import kotlin.random.Random

/**
 * Pure-logic 3×3 scratchcard. No Spring, no DB, no JDA.
 *
 * Mechanic
 *   Nine cells drawn independently from a weighted reel (same shape as
 *   [SlotMachine]: 4🍒 + 3🍋 + 2🔔 + 1⭐ = 10 cells). Win condition:
 *   5 or more cells of the SAME symbol anywhere on the card.
 *   Payout multiplier = basePayout × (matchCount − (MATCH_THRESHOLD − 1)) —
 *   pure closed form, every (symbol, k) pair distinct, no special cases.
 *   So 5🍒 = 1× (stake refund / push, net 0), 5🍋 = 2×, … 9⭐ = 200×.
 *
 *   Two symbols both hitting ≥5 is impossible with 9 cells (5+5 > 9),
 *   so the tie-break in `scratch` is a guard rather than a gameplay
 *   knob.
 *
 * Differentiates from /slots:
 *   - 9 cells in a 3×3 grid vs 3 reels → wider hit space, big-card feel
 *   - "≥5 of a kind" vs "exactly 3 of a kind" → counts of 6–9 scale
 *     payouts up linearly
 *   - RTP ≈ 0.875 (12.5% house edge), pinned by [expectedRtp] in tests
 */
class ScratchCard(
    private val reel: List<SlotMachine.Symbol> = SlotMachine.DEFAULT_REEL,
    private val basePayouts: Map<SlotMachine.Symbol, Long> = DEFAULT_BASE_PAYOUTS
) {

    data class Scratch(
        val cells: List<SlotMachine.Symbol>,
        val winningSymbol: SlotMachine.Symbol?,
        val matchCount: Int,
        val multiplier: Long
    ) {
        val isWin: Boolean get() = multiplier > 0L
    }

    fun scratch(random: Random): Scratch {
        val cells = List(CELL_COUNT) { reel[random.nextInt(reel.size)] }
        val counts = cells.groupingBy { it }.eachCount()
        val winner = counts.entries
            .filter { it.value >= MATCH_THRESHOLD }
            .maxWithOrNull(compareBy({ it.value }, { basePayouts[it.key] ?: 0L }))
        return if (winner != null) {
            val multiplier = multiplierFor(winner.key, winner.value, basePayouts)
            Scratch(cells = cells, winningSymbol = winner.key, matchCount = winner.value, multiplier = multiplier)
        } else {
            Scratch(cells = cells, winningSymbol = null, matchCount = 0, multiplier = 0L)
        }
    }

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L
        const val CELL_COUNT: Int = 9
        // 5-of-a-kind on a 9-cell card. The ratio threshold/cells (~0.55)
        // keeps win rate around 38% while still feeling skill-of-luck-y.
        // Earlier 5-cell rev sat at threshold 4 — moving to 9 cells without
        // bumping threshold would have gone back to wins-too-often.
        const val MATCH_THRESHOLD: Int = 5

        // Base multipliers. Effective multiplier on a k-of-a-kind is
        // base × (k - 4). 5🍒=1× is a stake-refund push (net 0), 5🍋=2×
        // is the smallest real win — every (symbol, k) pair is distinct.
        // Tuned alongside [expectedRtp]: adjust bases here, the RTP test
        // pins the closed-form result so drift surfaces in CI.
        val DEFAULT_BASE_PAYOUTS: Map<SlotMachine.Symbol, Long> = mapOf(
            SlotMachine.Symbol.CHERRY to 1L,    // 5🍒=1× (push), …, 9🍒=5×
            SlotMachine.Symbol.LEMON to 2L,     // 5🍋=2×,  …, 9🍋=10×
            SlotMachine.Symbol.BELL to 8L,      // 5🔔=8×,  …, 9🔔=40×
            SlotMachine.Symbol.STAR to 40L      // 5⭐=40×, …, 9⭐=200×
        )

        /**
         * Closed-form multiplier for [matchCount]-of-a-kind on [symbol].
         * Single source of truth — the live [scratch] path and any UI
         * that displays a payout table both call this so they can't drift.
         */
        fun multiplierFor(
            symbol: SlotMachine.Symbol,
            matchCount: Int,
            basePayouts: Map<SlotMachine.Symbol, Long> = DEFAULT_BASE_PAYOUTS
        ): Long {
            if (matchCount < MATCH_THRESHOLD) return 0L
            val base = basePayouts[symbol] ?: 0L
            return base * (matchCount - (MATCH_THRESHOLD - 1))
        }

        /**
         * Closed-form RTP given [reel], [basePayouts], and the card
         * geometry. For each symbol s, sums the binomial probability of
         * exactly k matches across [cellCount] independent draws (with
         * p_s = reel.count(s) / reel.size) times [multiplierFor]`(s, k)`,
         * over k ∈ [threshold..cellCount]. Two symbols both reaching
         * threshold is impossible when threshold > cellCount/2, so the
         * per-symbol sums don't double-count.
         *
         * Pure function — used by tests as the RTP source of truth so any
         * tuning that drifts the house edge fails CI loudly.
         */
        fun expectedRtp(
            reel: List<SlotMachine.Symbol> = SlotMachine.DEFAULT_REEL,
            basePayouts: Map<SlotMachine.Symbol, Long> = DEFAULT_BASE_PAYOUTS,
            cellCount: Int = CELL_COUNT,
            threshold: Int = MATCH_THRESHOLD
        ): Double {
            val total = reel.size.toDouble()
            return SlotMachine.Symbol.entries.sumOf { symbol ->
                val p = reel.count { it == symbol } / total
                if (p == 0.0) 0.0 else {
                    (threshold..cellCount).sumOf { k ->
                        binomialPmf(cellCount, k, p) *
                            multiplierFor(symbol, k, basePayouts).toDouble()
                    }
                }
            }
        }

        private fun binomialPmf(n: Int, k: Int, p: Double): Double {
            if (k < 0 || k > n) return 0.0
            // C(n, k) computed iteratively to avoid factorial overflow on
            // moderate n; n=9 here so this is overkill, but it keeps the
            // helper safe to reuse for larger geometries.
            var coeff = 1.0
            for (i in 1..k) coeff = coeff * (n - i + 1) / i
            return coeff * Math.pow(p, k.toDouble()) * Math.pow(1.0 - p, (n - k).toDouble())
        }
    }
}
