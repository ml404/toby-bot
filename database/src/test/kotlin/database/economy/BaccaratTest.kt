package database.economy

import database.card.Card
import database.card.Deck
import database.card.Rank
import database.card.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BaccaratTest {

    private fun c(rank: Rank, suit: Suit = Suit.SPADES) = Card(rank, suit)

    // --- value helpers ---

    @Test
    fun `baccaratValue maps tens and faces to zero`() {
        assertEquals(0, c(Rank.TEN).baccaratValue())
        assertEquals(0, c(Rank.JACK).baccaratValue())
        assertEquals(0, c(Rank.QUEEN).baccaratValue())
        assertEquals(0, c(Rank.KING).baccaratValue())
    }

    @Test
    fun `baccaratValue maps ace to one and face cards 2-9 to their pip`() {
        assertEquals(1, c(Rank.ACE).baccaratValue())
        assertEquals(2, c(Rank.TWO).baccaratValue())
        assertEquals(5, c(Rank.FIVE).baccaratValue())
        assertEquals(9, c(Rank.NINE).baccaratValue())
    }

    @Test
    fun `handTotal is the sum of pip values mod 10`() {
        // 7 + 8 = 15 → 5
        assertEquals(5, handTotal(listOf(c(Rank.SEVEN), c(Rank.EIGHT))))
        // K (0) + 9 = 9
        assertEquals(9, handTotal(listOf(c(Rank.KING), c(Rank.NINE))))
        // 9 + 9 + 9 = 27 → 7
        assertEquals(7, handTotal(listOf(c(Rank.NINE), c(Rank.NINE), c(Rank.NINE))))
    }

    // --- naturals ---

    @Test
    fun `player natural eight stops the deal — neither side draws a third card`() {
        val deck = mockDeck(
            c(Rank.FIVE), c(Rank.THREE), // player → 8 natural
            c(Rank.TWO), c(Rank.FOUR),   // banker → 6
            c(Rank.ACE)                  // poison: must never be drawn
        )
        val hand = Baccarat().play(Baccarat.Side.PLAYER, deck)

        assertEquals(8, hand.playerTotal)
        assertEquals(6, hand.bankerTotal)
        assertEquals(2, hand.playerCards.size)
        assertEquals(2, hand.bankerCards.size)
        assertTrue(hand.isPlayerNatural)
        assertFalse(hand.isBankerNatural)
        assertEquals(Baccarat.Side.PLAYER, hand.winner)
    }

    @Test
    fun `banker natural nine stops the deal — player does not draw even on zero`() {
        val deck = mockDeck(
            c(Rank.FIVE), c(Rank.FIVE), // player → 0 (would otherwise draw)
            c(Rank.FOUR), c(Rank.FIVE), // banker → 9, natural
            c(Rank.ACE)                  // poison
        )
        val hand = Baccarat().play(Baccarat.Side.PLAYER, deck)

        assertEquals(0, hand.playerTotal)
        assertEquals(9, hand.bankerTotal)
        assertEquals(2, hand.playerCards.size)
        assertEquals(2, hand.bankerCards.size)
        assertTrue(hand.isBankerNatural)
        assertEquals(Baccarat.Side.BANKER, hand.winner)
    }

    @Test
    fun `naturals tie when both sides land on eight`() {
        val deck = mockDeck(
            c(Rank.FIVE), c(Rank.THREE), // player → 8
            c(Rank.TWO), c(Rank.SIX)     // banker → 8
        )
        val hand = Baccarat().play(Baccarat.Side.TIE, deck)

        assertEquals(Baccarat.Side.TIE, hand.winner)
        assertTrue(hand.isPlayerNatural)
        assertTrue(hand.isBankerNatural)
        // Tie on TIE side bet pays at the natural multiplier.
        assertEquals(Baccarat.TIE_WIN_MULT, hand.multiplier, 1e-9)
    }

    // --- player draw rule ---

    @Test
    fun `player draws a third card on totals 0-5`() {
        // Player 1+3 = 4 → must draw. Banker 2+5 = 7 → stays.
        val deck = mockDeck(
            c(Rank.ACE), c(Rank.THREE),  // player → 4
            c(Rank.TWO), c(Rank.FIVE),   // banker → 7
            c(Rank.SIX)                  // player's third
        )
        val hand = Baccarat().play(Baccarat.Side.PLAYER, deck)
        assertEquals(3, hand.playerCards.size)
        // 4 + 6 = 10 → 0
        assertEquals(0, hand.playerTotal)
        assertEquals(2, hand.bankerCards.size)
    }

    @Test
    fun `player stands on totals 6-7`() {
        // Player 5+2 = 7 → stand. Banker 1+5 = 6 → would normally draw,
        // but with no Player third card and total 6, banker stands.
        val deck = mockDeck(
            c(Rank.FIVE), c(Rank.TWO),   // player → 7
            c(Rank.ACE), c(Rank.FIVE)    // banker → 6
            // no third cards — assert by deck not being asked
        )
        val hand = Baccarat().play(Baccarat.Side.PLAYER, deck)
        assertEquals(2, hand.playerCards.size)
        assertEquals(2, hand.bankerCards.size)
        assertEquals(7, hand.playerTotal)
        assertEquals(6, hand.bankerTotal)
        assertEquals(Baccarat.Side.PLAYER, hand.winner)
    }

    // --- banker tableau ---

    @Test
    fun `banker draws on total 0-5 when player did not draw a third`() {
        // Player 4+2 = 6 → stand. Banker 2+3 = 5 → draws on no-player-third.
        val deck = mockDeck(
            c(Rank.FOUR), c(Rank.TWO),   // player → 6 stand
            c(Rank.TWO), c(Rank.THREE),  // banker → 5
            c(Rank.NINE)                 // banker's third
        )
        val hand = Baccarat().play(Baccarat.Side.BANKER, deck)
        assertEquals(2, hand.playerCards.size)
        assertEquals(3, hand.bankerCards.size)
        // 5 + 9 = 14 → 4
        assertEquals(4, hand.bankerTotal)
        assertEquals(Baccarat.Side.PLAYER, hand.winner)
    }

    @Test
    fun `banker stands on 7 even with a player third card`() {
        // Player 1+1 = 2 → draws. Banker 5+2 = 7 → never draws.
        val deck = mockDeck(
            c(Rank.ACE), c(Rank.ACE),    // player → 2
            c(Rank.FIVE), c(Rank.TWO),   // banker → 7 stand
            c(Rank.NINE)                 // player's third → 2+9=1
            // no banker third — banker stands on 7
        )
        val hand = Baccarat().play(Baccarat.Side.PLAYER, deck)
        assertEquals(2, hand.bankerCards.size)
        assertEquals(7, hand.bankerTotal)
        assertEquals(1, hand.playerTotal)
        assertEquals(Baccarat.Side.BANKER, hand.winner)
    }

    @Test
    fun `banker on 3 stays only when player third is an 8`() {
        // Player 5+0 = 5 → draws. Banker 2+1 = 3.
        // Player third = 8 (rank EIGHT) → banker should NOT draw.
        val stayDeck = mockDeck(
            c(Rank.FIVE), c(Rank.TEN),   // player → 5
            c(Rank.TWO), c(Rank.ACE),    // banker → 3
            c(Rank.EIGHT)                // player third → 8 (banker stays)
        )
        val stayHand = Baccarat().play(Baccarat.Side.PLAYER, stayDeck)
        assertEquals(2, stayHand.bankerCards.size)
        assertEquals(3, stayHand.bankerTotal)

        // Player third = 7 → banker should draw.
        val drawDeck = mockDeck(
            c(Rank.FIVE), c(Rank.TEN),   // player → 5
            c(Rank.TWO), c(Rank.ACE),    // banker → 3
            c(Rank.SEVEN),               // player third → 7
            c(Rank.TWO)                  // banker third
        )
        val drawHand = Baccarat().play(Baccarat.Side.PLAYER, drawDeck)
        assertEquals(3, drawHand.bankerCards.size)
    }

    @Test
    fun `banker on 6 only draws when player third is 6 or 7`() {
        // Banker 6 stays on player-third 5.
        val stayDeck = mockDeck(
            c(Rank.FIVE), c(Rank.TEN),   // player → 5
            c(Rank.THREE), c(Rank.THREE),// banker → 6
            c(Rank.FIVE)                 // player third → 5 → banker stays
        )
        val stay = Baccarat().play(Baccarat.Side.PLAYER, stayDeck)
        assertEquals(2, stay.bankerCards.size)

        // Banker 6 draws on player-third 7.
        val drawDeck = mockDeck(
            c(Rank.FIVE), c(Rank.TEN),   // player → 5
            c(Rank.THREE), c(Rank.THREE),// banker → 6
            c(Rank.SEVEN),               // player third → 7 → banker draws
            c(Rank.TWO)                  // banker third
        )
        val draw = Baccarat().play(Baccarat.Side.PLAYER, drawDeck)
        assertEquals(3, draw.bankerCards.size)
    }

    // --- multipliers ---

    @Test
    fun `multiplier returns the side payout when the chosen side wins`() {
        val b = Baccarat()
        assertEquals(Baccarat.PLAYER_WIN_MULT, b.multiplier(Baccarat.Side.PLAYER, Baccarat.Side.PLAYER), 1e-9)
        assertEquals(Baccarat.BANKER_WIN_MULT, b.multiplier(Baccarat.Side.BANKER, Baccarat.Side.BANKER), 1e-9)
        assertEquals(Baccarat.TIE_WIN_MULT, b.multiplier(Baccarat.Side.TIE, Baccarat.Side.TIE), 1e-9)
    }

    @Test
    fun `multiplier returns zero when the chosen side loses outright`() {
        val b = Baccarat()
        assertEquals(0.0, b.multiplier(Baccarat.Side.PLAYER, Baccarat.Side.BANKER), 1e-9)
        assertEquals(0.0, b.multiplier(Baccarat.Side.BANKER, Baccarat.Side.PLAYER), 1e-9)
        // Tie loses on a non-tie game:
        assertEquals(0.0, b.multiplier(Baccarat.Side.TIE, Baccarat.Side.PLAYER), 1e-9)
        assertEquals(0.0, b.multiplier(Baccarat.Side.TIE, Baccarat.Side.BANKER), 1e-9)
    }

    @Test
    fun `multiplier pushes Player and Banker side bets on a tied game`() {
        val b = Baccarat()
        assertEquals(Baccarat.PUSH_MULT, b.multiplier(Baccarat.Side.PLAYER, Baccarat.Side.TIE), 1e-9)
        assertEquals(Baccarat.PUSH_MULT, b.multiplier(Baccarat.Side.BANKER, Baccarat.Side.TIE), 1e-9)
    }

    @Test
    fun `previewMultiplier exposes the configured payouts for button labels`() {
        val b = Baccarat()
        assertEquals(2.0, b.previewMultiplier(Baccarat.Side.PLAYER), 1e-9)
        assertEquals(1.95, b.previewMultiplier(Baccarat.Side.BANKER), 1e-9)
        assertEquals(9.0, b.previewMultiplier(Baccarat.Side.TIE), 1e-9)
    }

    // --- RTP smoke ---

    @Test
    fun `RTP across 50k random hands stays close to the published edge`() {
        val baccarat = Baccarat()
        val rng = Random(2026)
        val stake = 1_000L
        val n = 50_000
        var totalWagered = 0L
        var totalReturned = 0.0
        repeat(n) {
            val side = Baccarat.Side.entries[rng.nextInt(Baccarat.Side.entries.size)]
            val hand = baccarat.play(side, Deck(rng))
            totalWagered += stake
            totalReturned += hand.multiplier * stake
        }
        val rtp = totalReturned / totalWagered.toDouble()
        // Mixed across all three sides — Tie's ~85% drags the average below
        // Player/Banker's ~98%. Looser tolerance because Tie has wide
        // variance over 50k hands.
        assertTrue(rtp in 0.80..1.05, "expected mixed-side RTP in 0.80..1.05 but saw $rtp")
    }

    /**
     * Test helper — a Deck whose dealt cards are the supplied [seq],
     * in order. Calls beyond the sequence will throw via the underlying
     * ArrayDeque. Mirrors the same reflective swap used by
     * [database.blackjack.BlackjackTest].
     */
    private fun mockDeck(vararg seq: Card): Deck {
        val deck = Deck(Random(0))
        val field = Deck::class.java.getDeclaredField("cards")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cards = field.get(deck) as ArrayDeque<Card>
        cards.clear()
        cards.addAll(seq)
        return deck
    }
}
