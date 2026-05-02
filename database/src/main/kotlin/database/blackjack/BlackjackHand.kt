package database.blackjack

import database.card.Card
import database.card.Rank

/**
 * Blackjack-specific card valuation. Lives alongside the rules engine
 * (rather than inside [database.card.Card]) because the existing
 * `Card` enum is shared with poker, where Jack=11/Queen=12/King=13/Ace=14
 * are meaningful for hand ranking. Blackjack collapses face cards to 10
 * and lets aces count as either 1 or 11, which would conflict with the
 * poker semantics if folded into the enum itself.
 */
fun Card.blackjackValues(): List<Int> = when (rank) {
    Rank.ACE -> listOf(1, 11)
    Rank.JACK, Rank.QUEEN, Rank.KING -> listOf(10)
    else -> listOf(rank.value)
}

/**
 * Best non-bust total for [hand], or the lowest bust total if every
 * combination of ace values exceeds 21. Returns 0 for an empty hand
 * so callers can render a fresh seat without a special case.
 *
 * Aces start at 1 (the safe value) and one is promoted to 11 (+10) at
 * a time while the running total stays ≤21. Multiple aces can never
 * all be 11 — A-A is 12, not 22.
 */
fun bestTotal(hand: List<Card>): Int {
    if (hand.isEmpty()) return 0
    var total = hand.sumOf { card ->
        if (card.rank == Rank.ACE) 1 else card.blackjackValues().first()
    }
    var promotableAces = hand.count { it.rank == Rank.ACE }
    while (promotableAces > 0 && total + 10 <= 21) {
        total += 10
        promotableAces--
    }
    return total
}

/**
 * True when at least one ace in [hand] is currently being counted as
 * 11 in [bestTotal]. Used by the dealer's S17 rule (stand on all 17,
 * including soft) and by the hand-state embed to label "soft 17".
 */
fun isSoft(hand: List<Card>): Boolean {
    val aces = hand.count { it.rank == Rank.ACE }
    if (aces == 0) return false
    val hardTotal = hand.sumOf { card ->
        if (card.rank == Rank.ACE) 1 else card.blackjackValues().first()
    }
    return hardTotal + 10 <= 21
}

fun isBust(hand: List<Card>): Boolean = bestTotal(hand) > 21

/**
 * Natural blackjack — exactly two cards totalling 21 (an ace and any
 * 10-valued card). A 21 reached by hitting (e.g. 7-6-8) is a regular
 * 21, not a blackjack, and pays 1:1 not 3:2.
 */
fun isBlackjack(hand: List<Card>): Boolean =
    hand.size == 2 && bestTotal(hand) == 21
