package database.economy

import kotlin.random.Random

/**
 * Pure-logic keno. No Spring, no DB, no JDA — given a set of picks and
 * a list of drawn numbers, looks up the multiplier from the paytable
 * keyed on (spots, hits). The number drawing itself is delegated to
 * [drawNumbers] so the service can inject a deterministic [Random] for
 * tests while still keeping engine logic pure.
 *
 * Mechanic
 *   The player chooses N numbers ("spots") between 1 and 80 — between
 *   [MIN_SPOTS] and [MAX_SPOTS] of them — and the house draws [DRAWS]
 *   numbers from the same 1..80 pool without replacement. The number of
 *   the player's picks that match the draws ("hits") is looked up in
 *   [PAYTABLE]; the resulting multiplier is applied to the stake.
 *
 *   Bigger spot counts allow bigger top-end multipliers (10-spot maxes
 *   out at 100,000×) but require more hits to start paying — a 5-spot
 *   ticket starts paying at 3 hits, a 10-spot ticket needs 5+. Across
 *   spot counts the schedule sits at ~88-91% RTP, kept honest by
 *   [database.economy.KenoTest]'s RTP test that derives every pay from
 *   the hypergeometric distribution and asserts the sum lands in band.
 */
class Keno(private val paytable: Map<Int, List<Double>> = PAYTABLE) {

    /** Resolved hand: which picks landed, what multiplier applies. */
    data class Hand(
        val picks: List<Int>,
        val draws: List<Int>,
        val hits: Int,
        val multiplier: Double
    ) {
        val isWin: Boolean get() = multiplier > 0.0
    }

    /**
     * Validate [picks] / [draws] and resolve the hand. Picks must be a
     * non-empty distinct set in [POOL_RANGE] and within
     * [MIN_SPOTS]..[MAX_SPOTS]; draws must be [DRAWS] distinct values in
     * [POOL_RANGE]. Returns null on invalid input so the service can
     * surface the typed error variant to its caller.
     */
    fun play(picks: Set<Int>, draws: List<Int>): Hand? {
        if (picks.size !in MIN_SPOTS..MAX_SPOTS) return null
        if (picks.any { it !in POOL_RANGE }) return null
        if (draws.size != DRAWS) return null
        if (draws.toSet().size != DRAWS) return null
        if (draws.any { it !in POOL_RANGE }) return null
        val hits = picks.count { it in draws }
        val multiplier = multiplierFor(picks.size, hits)
        return Hand(
            picks = picks.sorted(),
            draws = draws,
            hits = hits,
            multiplier = multiplier
        )
    }

    /** Look up the payout multiplier; 0.0 if no row covers (spots, hits). */
    fun multiplierFor(spots: Int, hits: Int): Double {
        val row = paytable[spots] ?: return 0.0
        return row.getOrElse(hits) { 0.0 }
    }

    /** Top-end multiplier for a spot count — used in embed/UI labels. */
    fun maxMultiplier(spots: Int): Double = paytable[spots]?.maxOrNull() ?: 0.0

    /**
     * Draw [DRAWS] distinct numbers from [POOL_RANGE] using [random].
     * Reservoir-style — shuffles the full pool and takes the first
     * twenty. Cheap (80 elements) and avoids the duplicate-rejection
     * loops that bias tail picks under partial-knowledge generators.
     */
    fun drawNumbers(random: Random): List<Int> =
        POOL_RANGE.toMutableList().apply { shuffle(random) }.take(DRAWS)

    /**
     * Auto-pick [count] distinct numbers from [POOL_RANGE]. Reservoir
     * style for the same reason as [drawNumbers].
     */
    fun quickPick(count: Int, random: Random): List<Int> {
        require(count in MIN_SPOTS..MAX_SPOTS) {
            "quickPick count must be in $MIN_SPOTS..$MAX_SPOTS, got $count"
        }
        return POOL_RANGE.toMutableList().apply { shuffle(random) }.take(count).sorted()
    }

    companion object {
        const val POOL_SIZE: Int = 80
        const val DRAWS: Int = 20
        const val MIN_SPOTS: Int = 1
        const val MAX_SPOTS: Int = 10
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        /** Inclusive range of valid pick / draw values: 1..80. */
        val POOL_RANGE: IntRange = 1..POOL_SIZE

        /**
         * Per-spots paytable — `PAYTABLE[spots][hits]` is the multiplier
         * applied to the stake when a ticket of that many spots lands
         * exactly that many hits. List length is `spots + 1` so the
         * `hits=0` cell is always present and obviously zero.
         *
         * RTPs (computed in [database.economy.KenoTest]) sit in the
         * 0.83-0.92 band — slots tier, kept honest by the RTP test that
         * derives every pay from the hypergeometric distribution and
         * asserts the sum lands in band. 1-spot is intentionally lower
         * (~0.875): single-number bets have a structurally high house
         * edge in every keno schedule, raising it would force absurd
         * floor multipliers on the multi-spot rows.
         */
        val PAYTABLE: Map<Int, List<Double>> = mapOf(
            1  to listOf(0.0, 3.5),
            2  to listOf(0.0, 0.0, 14.6),
            3  to listOf(0.0, 0.0, 1.0, 53.0),
            4  to listOf(0.0, 0.0, 1.0, 8.0, 100.0),
            5  to listOf(0.0, 0.0, 0.0, 2.0, 15.0, 800.0),
            6  to listOf(0.0, 0.0, 0.0, 1.0, 7.0, 100.0, 1_900.0),
            7  to listOf(0.0, 0.0, 0.0, 0.0, 2.0, 28.0, 500.0, 7_000.0),
            8  to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 10.0, 115.0, 1_900.0, 28_500.0),
            9  to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 6.0, 53.0, 320.0, 4_250.0, 53_000.0),
            10 to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 28.0, 165.0, 1_650.0, 8_300.0, 165_000.0),
        )
    }
}
