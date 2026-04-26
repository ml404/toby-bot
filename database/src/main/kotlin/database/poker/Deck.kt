package database.poker

import kotlin.random.Random

/**
 * Mutable 52-card deck. Cards are dealt off the top until the deck is
 * empty. Pass a seeded [Random] for deterministic shuffles in tests
 * (mirrors how `DuelService` injects `Random.Default` for production
 * and a deterministic stub in unit tests).
 */
class Deck(random: Random = Random.Default) {
    private val cards: ArrayDeque<Card> = ArrayDeque(Card.all().shuffled(random))

    fun deal(): Card = cards.removeFirst()

    fun deal(count: Int): List<Card> = List(count) { deal() }

    val size: Int get() = cards.size
}
