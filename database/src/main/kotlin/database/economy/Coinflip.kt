package database.economy

import kotlin.random.Random

/**
 * Pure-logic coin flip. No Spring, no DB, no JDA — just a random binary
 * draw and a comparison against the caller's prediction.
 *
 * Mechanic
 *   Fair 50/50 coin. User picks a side; if the flip matches, the payout
 *   is `2 × stake` (i.e. they keep their stake AND win an equal amount).
 *   No house edge — `/coinflip` is the "double or nothing" pivot in the
 *   minigame portfolio. `/slots` is the actual credit sink (~11% house
 *   edge).
 *
 * Stake bounds (`MIN_STAKE` / `MAX_STAKE`) live here so the Discord
 * command, the web controller, and the service all agree on what's
 * valid. Higher MAX than `/slots` because the max payout is just 2×
 * (1,000 → 2,000 credits) — meaningful all-in without being
 * server-breaking.
 */
class Coinflip(
    private val multiplier: Long = DEFAULT_MULTIPLIER
) {

    enum class Side(val display: String) {
        HEADS("Heads"),
        TAILS("Tails")
    }

    data class Flip(
        val landed: Side,
        val predicted: Side,
        val multiplier: Long
    ) {
        val isWin: Boolean get() = landed == predicted
    }

    fun flip(predicted: Side, random: Random): Flip {
        val landed = if (random.nextBoolean()) Side.HEADS else Side.TAILS
        return Flip(
            landed = landed,
            predicted = predicted,
            multiplier = if (landed == predicted) multiplier else 0L
        )
    }

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 1_000L
        // 2× = stake doubled on win. RTP = 1.0 (no house edge by design).
        const val DEFAULT_MULTIPLIER: Long = 2L
    }
}
