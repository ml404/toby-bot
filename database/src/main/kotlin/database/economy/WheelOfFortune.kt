package database.economy

import kotlin.random.Random

/**
 * Pure-logic Wheel of Fortune: a weighted spinner where the player picks
 * one of the marked multipliers ahead of the spin and wins
 * `multiplier × stake` only if the wheel lands on that exact multiplier.
 * No Spring, no DB, no JDA — just a weighted draw and an equality check.
 *
 * Mechanic
 *   Player picks a multiplier from [PICKS] (2×, 3×, 5×, 10×). Wheel has
 *   100 slots distributed as per [DEFAULT_WEIGHTS]:
 *
 *     2×  — 44 slots  (P=0.44, per-pick RTP = 0.88)
 *     3×  — 29 slots  (P=0.29, per-pick RTP = 0.87)
 *     5×  — 18 slots  (P=0.18, per-pick RTP = 0.90)
 *    10×  —  9 slots  (P=0.09, per-pick RTP = 0.90)
 *
 *   Weights are roughly inversely proportional to multipliers so each
 *   pick has RTP near the same ~0.88 target — the 2× pick is the safest
 *   and most common, the 10× pick is the rarest and biggest. Average
 *   per-pick RTP ≈ 0.885.
 *
 * Single-spin "land" is independent of the player's pick — the wheel
 * doesn't know what was bet on. Caller compares the landed multiplier
 * to the picked multiplier to determine a win.
 *
 * Stake bounds (`MIN_STAKE` / `MAX_STAKE`) live here so the Discord
 * command, the web controller, and the service all agree on what's valid.
 */
class WheelOfFortune(
    private val weights: Map<Long, Int> = DEFAULT_WEIGHTS
) {

    init {
        require(weights.isNotEmpty()) { "wheel must have at least one slot" }
        require(weights.keys.all { it > 1L }) {
            "every slot multiplier must be > 1; got ${weights.keys}"
        }
        require(weights.values.all { it > 0 }) {
            "every slot weight must be > 0; got ${weights.values}"
        }
    }

    data class Spin(val landedMultiplier: Long, val pickedMultiplier: Long) {
        val isWin: Boolean get() = landedMultiplier == pickedMultiplier
    }

    /**
     * Spins the wheel once and compares against [pickedMultiplier].
     * On a hit (`landed == picked`), [Spin.isWin] is true and the
     * caller credits `picked × stake` to the player.
     */
    fun spin(pickedMultiplier: Long, random: Random): Spin {
        require(isValidPick(pickedMultiplier)) {
            "picked multiplier $pickedMultiplier is not in ${picks()}"
        }
        val total = weights.values.sum()
        val draw = random.nextInt(total)
        var running = 0
        for ((mult, w) in weights.entries.sortedBy { it.key }) {
            running += w
            if (draw < running) return Spin(landedMultiplier = mult, pickedMultiplier = pickedMultiplier)
        }
        // Unreachable: draw < total and running ends at total.
        val last = weights.entries.maxBy { it.key }.key
        return Spin(landedMultiplier = last, pickedMultiplier = pickedMultiplier)
    }

    fun isValidPick(pickedMultiplier: Long): Boolean = pickedMultiplier in weights.keys

    fun picks(): List<Long> = weights.keys.sorted()

    fun slotCount(): Int = weights.values.sum()

    fun weightFor(multiplier: Long): Int = weights[multiplier] ?: 0

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        /**
         * Wheel composition — 100 slots, sum-of-multiplier-mass ≈ 88 so
         * average per-pick RTP ≈ 0.88. Each pick's RTP is
         * `weight / 100 × multiplier`:
         *
         *   2×: 44/100 × 2  = 0.88
         *   3×: 29/100 × 3  = 0.87
         *   5×: 18/100 × 5  = 0.90
         *  10×:  9/100 × 10 = 0.90
         *
         * Pinned in [database.economy.WheelOfFortuneTest].
         */
        val DEFAULT_WEIGHTS: Map<Long, Int> = linkedMapOf(
            2L to 44,
            3L to 29,
            5L to 18,
            10L to 9,
        )

        /** Sorted list of pickable multipliers, exposed for the Discord
         *  command and web template. */
        val PICKS: List<Long> = DEFAULT_WEIGHTS.keys.sorted()
    }
}
