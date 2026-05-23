package common.poker

import common.card.Card
import common.card.Deck
import common.card.Rank
import kotlin.random.Random

/**
 * Pure-logic Casino Hold'em — the standard casino table variant of
 * Texas Hold'em against the house. Player antes, sees the flop, then
 * decides FOLD (forfeit ante) or CALL (commit `2 × stake` more) to
 * see the turn and river. Dealer must "qualify" with at least a pair
 * of fours for the call leg to be in play; otherwise the ante pays
 * even and the call pushes.
 *
 * No Spring, no DB, no JDA — just card maths and the per-leg
 * multiplier schedule. The owning [database.service.CasinoHoldemService]
 * holds the actual table state under a monitor.
 *
 * v1 schedule (call multiplier returns include the bet — e.g. 2.0 for
 * 1:1, 11.0 for 10:1):
 *   - Royal Flush      → 101.0 (100:1)
 *   - Straight Flush   →  21.0 (20:1)
 *   - Four of a Kind   →  11.0 (10:1)
 *   - Full House       →   4.0 (3:1)
 *   - Flush            →   3.0 (2:1)
 *   - Straight         →   2.0 (1:1)
 *   - Anything else win →  2.0 (1:1)
 *   - Push             →   1.0
 *   - Lose / Folded    →   0.0
 *
 * Ante leg is even-money on a win, push on a tie or non-qualified
 * dealer, zero on a loss / fold.
 */
class CasinoHoldem(private val random: Random = Random.Default) {

    enum class Action { CALL, FOLD }

    enum class AnteResult { WIN, PUSH, LOSE }

    enum class CallResult {
        WIN_ROYAL_FLUSH,
        WIN_STRAIGHT_FLUSH,
        WIN_QUADS,
        WIN_FULL_HOUSE,
        WIN_FLUSH,
        WIN_STRAIGHT,
        WIN_OTHER,
        PUSH,
        LOSE,
        FOLDED,
    }

    /**
     * Snapshot of one full deal — every card needed for showdown is
     * peeled up-front, even though the player only sees [flop] until
     * they CALL. This keeps the deal deterministic for tests and lets
     * the service stash the unrevealed cards on the table for the
     * eventual CALL path. Burn cards are skipped in v1.
     */
    data class Deal(
        val playerHole: List<Card>,
        val dealerHole: List<Card>,
        val flop: List<Card>,
        val turn: Card,
        val river: Card,
    )

    /** Resolution of a CALL'd hand. Folded hands skip this. */
    data class Resolution(
        val playerRank: HandEvaluator.HandRank,
        val dealerRank: HandEvaluator.HandRank,
        val dealerQualified: Boolean,
        val anteResult: AnteResult,
        val callResult: CallResult,
    )

    /** Fresh shuffled 52-card deck using the engine's RNG. */
    fun newDeck(): Deck = Deck(random)

    /**
     * Peel every card needed for showdown off [deck] in dealing order:
     * 2 player hole, 2 dealer hole, 3 flop, 1 turn, 1 river. The deck
     * is mutated so the caller passes a freshly seeded one per hand.
     */
    fun dealAll(deck: Deck): Deal {
        val playerHole = deck.deal(2)
        val dealerHole = deck.deal(2)
        val flop = deck.deal(3)
        val turn = deck.deal()
        val river = deck.deal()
        return Deal(playerHole, dealerHole, flop, turn, river)
    }

    /**
     * Casino Hold'em qualification rule: dealer must hold at least a
     * pair of fours among their best 5-of-7. Anything stronger than
     * a pair (two pair, trips, etc.) trivially qualifies; among bare
     * pairs, only fours-or-better counts.
     */
    fun dealerQualifies(rank: HandEvaluator.HandRank): Boolean {
        if (rank.category.ordinal > HandEvaluator.Category.PAIR.ordinal) return true
        if (rank.category != HandEvaluator.Category.PAIR) return false
        // PAIR's tiebreakers are [pair_rank, kicker_high, kicker_mid, kicker_low].
        val pairRank = rank.tiebreakers.firstOrNull() ?: return false
        return pairRank >= Rank.FOUR.value
    }

    /**
     * Run a CALL'd hand to showdown. Evaluates both sides via
     * [HandEvaluator.bestHand] over their hole cards plus the full
     * 5-card [board], applies dealer qualification, then maps the
     * outcome onto per-leg [AnteResult] and [CallResult] values.
     */
    fun resolve(
        playerHole: List<Card>,
        dealerHole: List<Card>,
        board: List<Card>,
    ): Resolution {
        require(board.size == 5) { "resolve needs the full 5-card board, got ${board.size}" }
        val playerRank = HandEvaluator.bestHand(playerHole, board)
        val dealerRank = HandEvaluator.bestHand(dealerHole, board)
        val qualified = dealerQualifies(dealerRank)

        // Dealer doesn't qualify: ante pays even, call pushes regardless
        // of who has the better hand. (Standard Casino Hold'em rule.)
        if (!qualified) {
            return Resolution(
                playerRank = playerRank,
                dealerRank = dealerRank,
                dealerQualified = false,
                anteResult = AnteResult.WIN,
                callResult = CallResult.PUSH,
            )
        }

        val cmp = playerRank.compareTo(dealerRank)
        return when {
            cmp > 0 -> Resolution(
                playerRank = playerRank,
                dealerRank = dealerRank,
                dealerQualified = true,
                anteResult = AnteResult.WIN,
                callResult = if (isRoyalFlush(playerRank)) CallResult.WIN_ROYAL_FLUSH
                else callResultForCategory(playerRank.category),
            )
            cmp < 0 -> Resolution(
                playerRank = playerRank,
                dealerRank = dealerRank,
                dealerQualified = true,
                anteResult = AnteResult.LOSE,
                callResult = CallResult.LOSE,
            )
            else -> Resolution(
                playerRank = playerRank,
                dealerRank = dealerRank,
                dealerQualified = true,
                anteResult = AnteResult.PUSH,
                callResult = CallResult.PUSH,
            )
        }
    }

    /**
     * Map the player's winning category to its [CallResult] so the
     * paytable lookup is a single switch. Royal flush is ace-high
     * straight flush — distinguished from a plain straight flush by
     * the tiebreaker high-card value (14 = ace).
     */
    private fun callResultForCategory(category: HandEvaluator.Category): CallResult = when (category) {
        HandEvaluator.Category.STRAIGHT_FLUSH -> CallResult.WIN_STRAIGHT_FLUSH
        HandEvaluator.Category.FOUR_OF_A_KIND -> CallResult.WIN_QUADS
        HandEvaluator.Category.FULL_HOUSE -> CallResult.WIN_FULL_HOUSE
        HandEvaluator.Category.FLUSH -> CallResult.WIN_FLUSH
        HandEvaluator.Category.STRAIGHT -> CallResult.WIN_STRAIGHT
        else -> CallResult.WIN_OTHER
    }

    /**
     * Royal-flush check: the rank's category is STRAIGHT_FLUSH and
     * its sole tiebreaker is the ace's value. Exposed so the service
     * can promote a generic straight-flush win to royal.
     */
    fun isRoyalFlush(rank: HandEvaluator.HandRank): Boolean =
        rank.category == HandEvaluator.Category.STRAIGHT_FLUSH &&
            rank.tiebreakers.firstOrNull() == Rank.ACE.value

    /** Multiplier on the ante leg, applied to the original `stake`. */
    fun anteMultiplier(result: AnteResult): Double = when (result) {
        AnteResult.WIN -> ANTE_WIN_MULT
        AnteResult.PUSH -> PUSH_MULT
        AnteResult.LOSE -> LOSS_MULT
    }

    /** Multiplier on the call leg, applied to `2 × stake`. */
    fun callMultiplier(result: CallResult): Double = when (result) {
        CallResult.WIN_ROYAL_FLUSH -> ROYAL_FLUSH_MULT
        CallResult.WIN_STRAIGHT_FLUSH -> STRAIGHT_FLUSH_MULT
        CallResult.WIN_QUADS -> QUADS_MULT
        CallResult.WIN_FULL_HOUSE -> FULL_HOUSE_MULT
        CallResult.WIN_FLUSH -> FLUSH_MULT
        CallResult.WIN_STRAIGHT -> STRAIGHT_MULT
        CallResult.WIN_OTHER -> CALL_WIN_MULT
        CallResult.PUSH -> PUSH_MULT
        CallResult.LOSE, CallResult.FOLDED -> LOSS_MULT
    }

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        /** CALL costs this many times the original stake. */
        const val CALL_MULTIPLE: Long = 2L

        const val ANTE_WIN_MULT: Double = 2.0
        const val CALL_WIN_MULT: Double = 2.0
        const val STRAIGHT_MULT: Double = 2.0
        const val FLUSH_MULT: Double = 3.0
        const val FULL_HOUSE_MULT: Double = 4.0
        const val QUADS_MULT: Double = 11.0
        const val STRAIGHT_FLUSH_MULT: Double = 21.0
        const val ROYAL_FLUSH_MULT: Double = 101.0
        const val PUSH_MULT: Double = 1.0
        const val LOSS_MULT: Double = 0.0
    }
}
