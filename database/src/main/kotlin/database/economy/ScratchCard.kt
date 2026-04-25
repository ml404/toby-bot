package database.economy

import kotlin.random.Random

/**
 * Pure-logic 3×3 scratchcard. No Spring, no DB, no JDA.
 *
 * Mechanic
 *   Nine cells drawn independently from a weighted reel (same shape as
 *   [SlotMachine]: 4🍒 + 3🍋 + 2🔔 + 1⭐ = 10 cells). Win condition:
 *   5 or more cells of the SAME symbol anywhere on the card.
 *   Payout multiplier = (basePayout for the matching symbol) ×
 *   (matchCount − (MATCH_THRESHOLD − 1)) — so 5-of-a-kind pays base, 6
 *   pays 2×, … 9 pays 5×.
 *
 *   Two symbols both hitting ≥5 is impossible with 9 cells (5+5 > 9),
 *   so the tie-break in `scratch` is a guard rather than a gameplay
 *   knob.
 *
 * Differentiates from /slots:
 *   - 9 cells in a 3×3 grid vs 3 reels → wider hit space, big-card feel
 *   - "≥5 of a kind" vs "exactly 3 of a kind" → counts of 6–9 scale
 *     payouts up linearly
 *   - Different RTP profile (~85-90% on these tunings; the RTP test in
 *     ScratchCardTest pins the actual number)
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
        const val CELL_COUNT: Int = 9
        // 5-of-a-kind on a 9-cell card. The ratio threshold/cells (~0.55)
        // keeps win rate around 38% while still feeling skill-of-luck-y.
        // Earlier 5-cell rev sat at threshold 4 — moving to 9 cells without
        // bumping threshold would have gone back to wins-too-often.
        const val MATCH_THRESHOLD: Int = 5

        // Base multipliers (paid on a 5-of-a-kind; each additional matching
        // cell adds another `base` to the multiplier — so 9⭐ = 5× base).
        // Tuned alongside the RTP convergence test — adjust here, rerun
        // ScratchCardTest's RTP case, accept the new bound.
        val DEFAULT_BASE_PAYOUTS: Map<SlotMachine.Symbol, Long> = mapOf(
            SlotMachine.Symbol.CHERRY to 1L,    // 5🍒=1×,  …, 9🍒=5×
            SlotMachine.Symbol.LEMON to 2L,     // 5🍋=2×,  …, 9🍋=10×
            SlotMachine.Symbol.BELL to 8L,      // 5🔔=8×,  …, 9🔔=40×
            SlotMachine.Symbol.STAR to 40L      // 5⭐=40×, …, 9⭐=200×
        )
    }
}
