package database.poker

import database.card.Card
import database.card.Rank

/**
 * Pure 5-from-7 Texas Hold'em hand evaluator. Given a player's two hole
 * cards plus up to five community cards, returns the best 5-card
 * [HandRank] reachable. [HandRank] is `Comparable`: higher category
 * wins, ties broken by descending kicker ranks.
 *
 * Wheel handling: A-2-3-4-5 is a straight with the Ace playing low; its
 * high card is 5, not the Ace. Likewise A-2-3-4-5 of one suit is a
 * straight flush valued at "5-high straight flush", strictly below
 * 6-high straight flush.
 */
object HandEvaluator {

    enum class Category {
        HIGH_CARD,
        PAIR,
        TWO_PAIR,
        THREE_OF_A_KIND,
        STRAIGHT,
        FLUSH,
        FULL_HOUSE,
        FOUR_OF_A_KIND,
        STRAIGHT_FLUSH
    }

    data class HandRank(
        val category: Category,
        val tiebreakers: List<Int>
    ) : Comparable<HandRank> {
        override fun compareTo(other: HandRank): Int {
            val byCategory = category.ordinal.compareTo(other.category.ordinal)
            if (byCategory != 0) return byCategory
            for (i in tiebreakers.indices) {
                if (i >= other.tiebreakers.size) return 1
                val c = tiebreakers[i].compareTo(other.tiebreakers[i])
                if (c != 0) return c
            }
            if (other.tiebreakers.size > tiebreakers.size) return -1
            return 0
        }
    }

    /**
     * Best [HandRank] across all 5-card subsets of [holeCards] + [board].
     * Either list may be partial (e.g. on the flop the board is 3 cards),
     * provided their union has at least 5 cards.
     */
    fun bestHand(holeCards: List<Card>, board: List<Card>): HandRank {
        val all = holeCards + board
        require(all.size >= 5) { "Need at least 5 cards to evaluate, got ${all.size}" }
        return combinations(all, 5).maxOf(::scoreFive)
    }

    /** Direct 5-card scoring; exposed for unit tests and short-circuit paths. */
    fun scoreFive(hand: List<Card>): HandRank {
        require(hand.size == 5) { "scoreFive needs exactly 5 cards" }
        val ranksDesc = hand.map { it.rank.value }.sortedDescending()
        val isFlush = hand.map { it.suit }.toSet().size == 1
        val straightHigh = straightHighCard(ranksDesc)

        val rankCounts = ranksDesc.groupingBy { it }.eachCount()
        val byCount = rankCounts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })

        if (isFlush && straightHigh != null) {
            return HandRank(Category.STRAIGHT_FLUSH, listOf(straightHigh))
        }
        if (byCount[0].value == 4) {
            val quad = byCount[0].key
            val kicker = byCount[1].key
            return HandRank(Category.FOUR_OF_A_KIND, listOf(quad, kicker))
        }
        if (byCount[0].value == 3 && byCount[1].value == 2) {
            return HandRank(Category.FULL_HOUSE, listOf(byCount[0].key, byCount[1].key))
        }
        if (isFlush) {
            return HandRank(Category.FLUSH, ranksDesc)
        }
        if (straightHigh != null) {
            return HandRank(Category.STRAIGHT, listOf(straightHigh))
        }
        if (byCount[0].value == 3) {
            val trip = byCount[0].key
            val kickers = byCount.drop(1).map { it.key }
            return HandRank(Category.THREE_OF_A_KIND, listOf(trip) + kickers)
        }
        if (byCount[0].value == 2 && byCount[1].value == 2) {
            val highPair = byCount[0].key
            val lowPair = byCount[1].key
            val kicker = byCount[2].key
            return HandRank(Category.TWO_PAIR, listOf(highPair, lowPair, kicker))
        }
        if (byCount[0].value == 2) {
            val pair = byCount[0].key
            val kickers = byCount.drop(1).map { it.key }
            return HandRank(Category.PAIR, listOf(pair) + kickers)
        }
        return HandRank(Category.HIGH_CARD, ranksDesc)
    }

    /** High card of a 5-card straight, or null if the hand isn't a straight. */
    private fun straightHighCard(ranksDesc: List<Int>): Int? {
        val unique = ranksDesc.toSortedSet()
        if (unique.size != 5) return null
        val sorted = unique.toList() // ascending
        val high = sorted.last()
        val low = sorted.first()
        if (high - low == 4) return high
        // Wheel: A-2-3-4-5 (Ace acts as 1).
        if (sorted == listOf(2, 3, 4, 5, Rank.ACE.value)) return 5
        return null
    }

    private fun <T> combinations(items: List<T>, k: Int): Sequence<List<T>> = sequence {
        if (k == 0) { yield(emptyList()); return@sequence }
        if (k > items.size) return@sequence
        val n = items.size
        val idx = IntArray(k) { it }
        while (true) {
            yield(idx.map { items[it] })
            var i = k - 1
            while (i >= 0 && idx[i] == n - k + i) i--
            if (i < 0) return@sequence
            idx[i]++
            for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
        }
    }
}
