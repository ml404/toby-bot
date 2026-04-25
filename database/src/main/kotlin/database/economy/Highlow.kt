package database.economy

import kotlin.random.Random

/**
 * Pure-logic high-low. No Spring, no DB, no JDA — just two uniform
 * card draws and a comparison against the caller's direction.
 *
 * Mechanic
 *   Server draws an anchor card (1..13). User commits HIGHER or LOWER.
 *   Server draws the next card. Win condition:
 *     - HIGHER → next > anchor
 *     - LOWER  → next < anchor
 *     - tie (next == anchor) → lose
 *   Payout is flat 2× on win. Average RTP ≈ 12/13 ≈ 0.923
 *   (~7.7% house edge from the tie-loses rule). Sits between
 *   /coinflip (no edge) and /slots (~11% edge).
 *
 * Two flows live here:
 *   - [play] — bundled draw used by Discord. Anchor + next come out
 *     together; the player has no chance to peek before committing.
 *   - [dealAnchor] + [resolve] — split flow used by the web page.
 *     Anchor is revealed first; the user picks direction having seen
 *     it. The web caller is responsible for persisting the anchor
 *     between requests (HttpSession in [HighlowController]) so the
 *     player can't reroll a fresh anchor by refreshing the page.
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

    /** Bundled draw — anchor + next in a single call. Discord uses this. */
    fun play(direction: Direction, random: Random): Hand {
        val anchor = dealAnchor(random)
        return resolve(anchor, direction, random)
    }

    /** Standalone anchor draw for the split web flow. */
    fun dealAnchor(random: Random): Int = random.nextInt(1, deckSize + 1)

    /**
     * Given an anchor that's already been revealed, draw the next card
     * and decide the outcome. Used by the web flow where the anchor was
     * shown before the user picked direction.
     */
    fun resolve(anchor: Int, direction: Direction, random: Random): Hand {
        require(anchor in 1..deckSize) { "anchor $anchor outside 1..$deckSize" }
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
