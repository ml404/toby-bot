package database.economy

import kotlin.random.Random

/**
 * Pure-logic high-low. No Spring, no DB, no JDA — just two uniform
 * card draws and a comparison against the caller's direction.
 *
 * Mechanic
 *   Server draws an anchor card (2..deckSize-1; extremes are clipped so
 *   every dealt hand is winnable in both directions). User commits
 *   HIGHER or LOWER. Server draws the next card from the full 1..deckSize
 *   range. Win condition:
 *     - HIGHER → next > anchor
 *     - LOWER  → next < anchor
 *     - tie (next == anchor) → lose
 *   Payout is anchor- and direction-aware: a winning bet pays
 *   `(deckSize - 1) / winningOutcomes` × stake. Average RTP per hand is
 *   `(deckSize - 1) / deckSize` ≈ 12/13 ≈ 0.923 regardless of which
 *   direction the player picks, so a player who can see the anchor (web
 *   flow) cannot exploit extreme anchors for a positive edge. Sits
 *   between /coinflip (no edge) and /slots (~11% edge).
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
    private val deckSize: Int = DEFAULT_DECK_SIZE
) {

    enum class Direction(val display: String) {
        HIGHER("Higher"),
        LOWER("Lower")
    }

    data class Hand(
        val anchor: Int,
        val next: Int,
        val direction: Direction,
        val multiplier: Double
    ) {
        val isWin: Boolean get() = multiplier > 0.0
    }

    /** Bundled draw — anchor + next in a single call. Discord uses this. */
    fun play(direction: Direction, random: Random): Hand {
        val anchor = dealAnchor(random)
        return resolve(anchor, direction, random)
    }

    /**
     * Standalone anchor draw for the split web flow. Capped to
     * 2..deckSize-1 so both HIGHER and LOWER are winnable for every
     * dealt anchor (the formula in [payoutMultiplier] would yield a 0
     * payout for an impossible direction or a 1× refund for the trivial
     * one on the extremes, which is poor UX).
     */
    fun dealAnchor(random: Random): Int = random.nextInt(2, deckSize)

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
            multiplier = if (won) payoutMultiplier(anchor, direction) else 0.0
        )
    }

    /**
     * Payout multiplier for a winning bet on [anchor] in [direction].
     * Equal to `(deckSize - 1) / winningOutcomes`, which holds RTP at
     * `(deckSize - 1) / deckSize` for every (anchor, direction) pair.
     * Returns `0.0` for an impossible direction (no winning outcomes),
     * which happens only on extreme anchors that [dealAnchor] does not
     * produce — kept defensive in case a stale session-stored anchor
     * reaches [resolve].
     */
    fun payoutMultiplier(anchor: Int, direction: Direction): Double {
        val winningOutcomes = when (direction) {
            Direction.HIGHER -> deckSize - anchor
            Direction.LOWER -> anchor - 1
        }
        if (winningOutcomes <= 0) return 0.0
        return (deckSize - 1).toDouble() / winningOutcomes
    }

    val deckSizeValue: Int get() = deckSize

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L
        const val DEFAULT_DECK_SIZE: Int = 13
    }
}
