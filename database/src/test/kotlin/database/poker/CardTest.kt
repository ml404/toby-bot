package database.poker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardTest {

    @Test
    fun `Card all returns 52 unique cards`() {
        val all = Card.all()
        assertEquals(52, all.size)
        assertEquals(52, all.toSet().size)
    }

    @Test
    fun `all 13 ranks across all 4 suits`() {
        val all = Card.all()
        assertEquals(4, all.count { it.rank == Rank.ACE })
        assertEquals(13, all.count { it.suit == Suit.SPADES })
    }

    @Test
    fun `Card toString joins rank symbol and suit symbol`() {
        assertEquals("A♠", Card(Rank.ACE, Suit.SPADES).toString())
        assertEquals("T♥", Card(Rank.TEN, Suit.HEARTS).toString())
    }

    @Test
    fun `Rank values are ordered weakest-to-strongest`() {
        assertTrue(Rank.TWO.value < Rank.JACK.value)
        assertTrue(Rank.JACK.value < Rank.ACE.value)
    }
}
