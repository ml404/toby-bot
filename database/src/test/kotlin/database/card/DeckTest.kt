package database.poker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DeckTest {

    @Test
    fun `fresh deck has 52 unique cards`() {
        val cards = Deck(Random(0)).run { deal(52) }
        assertEquals(52, cards.size)
        assertEquals(52, cards.toSet().size, "no duplicates")
    }

    @Test
    fun `same seed produces same shuffle`() {
        val a = Deck(Random(1234)).deal(10)
        val b = Deck(Random(1234)).deal(10)
        assertEquals(a, b)
    }

    @Test
    fun `different seeds produce different shuffles`() {
        val a = Deck(Random(1234)).deal(10)
        val b = Deck(Random(5678)).deal(10)
        assertNotEquals(a, b)
    }

    @Test
    fun `deal reduces deck size`() {
        val deck = Deck(Random(0))
        deck.deal(7)
        assertEquals(45, deck.size)
    }
}
