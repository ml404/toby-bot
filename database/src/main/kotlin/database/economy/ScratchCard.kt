package database.economy

import kotlin.random.Random

/**
 * Pure-logic 5-cell scratchcard. No Spring, no DB, no JDA.
 *
 * Mechanic
 *   Five cells drawn independently from a weighted reel (same shape as
 *   [SlotMachine]: 4🍒 + 3🍋 + 2🔔 + 1⭐ = 10 cells). Win condition:
 *   3 or more cells of the SAME symbol anywhere on the card.
 *   Payout multiplier = (basePayout for the matching symbol) ×
 *   (matchCount - 2) — so 3-of-a-kind pays the base, 4 pays 2×, 5 pays 3×.
 *
 *   When two symbols both hit ≥3 (impossible with 5 cells, since
 *   3+3 > 5), we'd take the rarer one — but it can't happen, so the
 *   tie-break is a guard rather than a gameplay knob.
 *
 * Differentiates from /slots:
 *   - 5 cells vs 3 → wider hit space, smaller-but-more-frequent wins
 *   - "≥3 of a kind" vs "exactly 3 of a kind" → counts of 4 and 5
 *     scale up payouts
 *   - Different RTP profile (around ~75-80% on these tunings; the
 *     RTP test in ScratchCardTest pins the actual number)
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
        // Find the symbol with the highest count. If two are tied at
        // the top (can't happen with 5 cells and a max of 2 different
        // ≥3 groups, but defend anyway), prefer the one with the higher
        // base payout so the player gets the better outcome.
        val counts = cells.groupingBy { it }.eachCount()
        val winner = counts.entries
            .filter { it.value >= MATCH_THRESHOLD }
            .maxWithOrNull(compareBy({ it.value }, { basePayouts[it.key] ?: 0L }))
        return if (winner != null) {
            val base = basePayouts[winner.key] ?: 0L
            val multiplier = base * (winner.value - (MATCH_THRESHOLD - 1))
            Scratch(cells = cells, winningSymbol = winner.key, matchCount = winner.value, multiplier = multiplier)
        } else {
            Scratch(cells = cells, winningSymbol = null, matchCount = 0, multiplier = 0L)
        }
    }

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L
        const val CELL_COUNT: Int = 5
        // 4-of-a-kind threshold (was 3 in the first rev — 3 led to ~1.78×
        // RTP because P(≥3 cherries) ≈ 0.317 dominated the EV. Bumping to
        // 4 drops the win rate to ~12% and lets the multiplier table land
        // RTP in the 85-91% range).
        const val MATCH_THRESHOLD: Int = 4

        // Base multipliers (paid on a 4-of-a-kind; doubled for 5). Tuned
        // alongside the RTP convergence test — adjust here, rerun
        // ScratchCardTest's RTP case, accept the new bound.
        val DEFAULT_BASE_PAYOUTS: Map<SlotMachine.Symbol, Long> = mapOf(
            SlotMachine.Symbol.CHERRY to 5L,    // 4🍒=5×,  5🍒=10×
            SlotMachine.Symbol.LEMON to 7L,     // 4🍋=7×,  5🍋=14×
            SlotMachine.Symbol.BELL to 22L,     // 4🔔=22×, 5🔔=44×
            SlotMachine.Symbol.STAR to 90L      // 4⭐=90×, 5⭐=180×
        )
    }
}
