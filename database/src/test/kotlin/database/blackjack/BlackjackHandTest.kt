package database.blackjack

import database.card.Card
import database.card.Rank
import database.card.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlackjackHandTest {

    private fun c(rank: Rank, suit: Suit = Suit.SPADES) = Card(rank, suit)

    @Test
    fun `bestTotal of empty hand returns 0`() {
        assertEquals(0, bestTotal(emptyList()))
    }

    @Test
    fun `bestTotal of K-7 returns 17`() {
        assertEquals(17, bestTotal(listOf(c(Rank.KING), c(Rank.SEVEN))))
    }

    @Test
    fun `bestTotal of A-K returns 21`() {
        assertEquals(21, bestTotal(listOf(c(Rank.ACE), c(Rank.KING))))
    }

    @Test
    fun `bestTotal of A-K-5 returns 16 with Ace as 1`() {
        assertEquals(16, bestTotal(listOf(c(Rank.ACE), c(Rank.KING), c(Rank.FIVE))))
    }

    @Test
    fun `bestTotal of A-A returns 12 (one Ace as 11, one as 1)`() {
        assertEquals(12, bestTotal(listOf(c(Rank.ACE), c(Rank.ACE, Suit.HEARTS))))
    }

    @Test
    fun `bestTotal of three Aces returns 13`() {
        assertEquals(13, bestTotal(listOf(c(Rank.ACE), c(Rank.ACE, Suit.HEARTS), c(Rank.ACE, Suit.CLUBS))))
    }

    @Test
    fun `bestTotal of K-Q-2 returns the bust total 22`() {
        assertEquals(22, bestTotal(listOf(c(Rank.KING), c(Rank.QUEEN), c(Rank.TWO))))
    }

    @Test
    fun `face cards count as 10`() {
        assertEquals(20, bestTotal(listOf(c(Rank.JACK), c(Rank.QUEEN))))
        assertEquals(20, bestTotal(listOf(c(Rank.KING), c(Rank.JACK))))
    }

    @Test
    fun `isSoft is true for A-6 and false after busting the soft total`() {
        assertTrue(isSoft(listOf(c(Rank.ACE), c(Rank.SIX))))
        // A-6 = soft 17. Adding K → 17 (Ace forced to 1) — no longer soft.
        assertFalse(isSoft(listOf(c(Rank.ACE), c(Rank.SIX), c(Rank.KING))))
    }

    @Test
    fun `isSoft is false for hands without an ace`() {
        assertFalse(isSoft(listOf(c(Rank.KING), c(Rank.SEVEN))))
    }

    @Test
    fun `isBust is true above 21 and false at or below`() {
        assertTrue(isBust(listOf(c(Rank.KING), c(Rank.QUEEN), c(Rank.TWO))))
        assertFalse(isBust(listOf(c(Rank.KING), c(Rank.QUEEN))))
        assertFalse(isBust(listOf(c(Rank.ACE), c(Rank.KING))))
    }

    @Test
    fun `isBlackjack only true for two-card 21`() {
        assertTrue(isBlackjack(listOf(c(Rank.ACE), c(Rank.KING))))
        assertTrue(isBlackjack(listOf(c(Rank.ACE), c(Rank.QUEEN, Suit.HEARTS))))
        // 21 reached with a hit is NOT a blackjack.
        assertFalse(isBlackjack(listOf(c(Rank.SEVEN), c(Rank.SEVEN, Suit.HEARTS), c(Rank.SEVEN, Suit.CLUBS))))
        assertFalse(isBlackjack(listOf(c(Rank.ACE))))
    }

    @Test
    fun `canSplit true for matching ranks`() {
        assertTrue(canSplit(listOf(c(Rank.EIGHT), c(Rank.EIGHT, Suit.HEARTS))))
        assertTrue(canSplit(listOf(c(Rank.ACE), c(Rank.ACE, Suit.HEARTS))))
    }

    @Test
    fun `canSplit true for ten-value pairs across face cards`() {
        // T-J-Q-K all count as 10 in blackjack, so any pair of them is splittable.
        assertTrue(canSplit(listOf(c(Rank.TEN), c(Rank.JACK))))
        assertTrue(canSplit(listOf(c(Rank.JACK), c(Rank.QUEEN))))
        assertTrue(canSplit(listOf(c(Rank.KING), c(Rank.TEN, Suit.HEARTS))))
    }

    @Test
    fun `canSplit false for different values`() {
        assertFalse(canSplit(listOf(c(Rank.NINE), c(Rank.TEN))))
        assertFalse(canSplit(listOf(c(Rank.ACE), c(Rank.KING))))
    }

    @Test
    fun `canSplit false for non-2-card hands`() {
        assertFalse(canSplit(emptyList()))
        assertFalse(canSplit(listOf(c(Rank.EIGHT))))
        assertFalse(canSplit(listOf(c(Rank.EIGHT), c(Rank.EIGHT, Suit.HEARTS), c(Rank.EIGHT, Suit.CLUBS))))
    }
}
