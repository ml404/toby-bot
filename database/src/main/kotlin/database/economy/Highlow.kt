package database.economy

import kotlin.random.Random

/**
 * Pure-logic high-low. No Spring, no DB, no JDA — just two uniform
 * card draws and a comparison against the caller's direction.
 *
 * Mechanic
 *   Server draws an anchor card (1..13). User pre-commits HIGHER or
 *   LOWER. Server draws the next card. Win condition:
 *     - HIGHER → next > anchor
 *     - LOWER  → next < anchor
 *     - tie (next == anchor) → lose
 *   Payout is flat 2× on win. Average RTP ≈ 12/13 ≈ 0.923
 *   (~7.7% house edge from the tie-loses rule). Sits between
 *   /coinflip (no edge) and /slots (~11% edge).
 *
 * Why pre-commit instead of "show card, then pick"
 *   Single API call, no sticky state, identical Discord and web shape.
 *   Anchor is part of the result, not a pre-state. Variance is real
 *   though — anchor=13 + HIGHER is a sure loss.
 */
class Highlow(
    private val deckSize: Int = DEFAULT_DECK_SIZE,
    private val multiplier: Long = DEFAULT_MULTIPLIER
) {

    enum class Direction(val display: String) {
        HIGHER("Higher"),
        LOWER("Lower")
    }

    data class Hand(
        val anchor: Int,
        val next: Int,
        val direction: Direction,
        val multiplier: Long
    ) {
        val isWin: Boolean get() = multiplier > 0L
    }

    fun play(direction: Direction, random: Random): Hand {
        val anchor = random.nextInt(1, deckSize + 1)
        val next = random.nextInt(1, deckSize + 1)
        val won = when (direction) {
            Direction.HIGHER -> next > anchor
            Direction.LOWER -> next < anchor
        }
        return Hand(
            anchor = anchor,
            next = next,
            direction = direction,
            multiplier = if (won) multiplier else 0L
        )
    }

    val deckSizeValue: Int get() = deckSize

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L
        const val DEFAULT_DECK_SIZE: Int = 13
        // 2× on win; with tie-loses the average win probability is ≈ 6/13,
        // so RTP ≈ 12/13 ≈ 0.923.
        const val DEFAULT_MULTIPLIER: Long = 2L
    }
}
