package database.poker

import database.card.Card
import database.card.Rank
import database.card.Suit
import database.poker.HandEvaluator.Category
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandEvaluatorTest {

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)
    private fun parse(notation: String): Card {
        // "AS" = ace of spades, "TH" = ten of hearts.
        val r = when (notation[0]) {
            '2' -> Rank.TWO; '3' -> Rank.THREE; '4' -> Rank.FOUR; '5' -> Rank.FIVE
            '6' -> Rank.SIX; '7' -> Rank.SEVEN; '8' -> Rank.EIGHT; '9' -> Rank.NINE
            'T' -> Rank.TEN; 'J' -> Rank.JACK; 'Q' -> Rank.QUEEN; 'K' -> Rank.KING
            'A' -> Rank.ACE; else -> error("bad rank in $notation")
        }
        val s = when (notation[1]) {
            'C' -> Suit.CLUBS; 'D' -> Suit.DIAMONDS; 'H' -> Suit.HEARTS; 'S' -> Suit.SPADES
            else -> error("bad suit in $notation")
        }
        return Card(r, s)
    }

    private fun hand(vararg notations: String): List<Card> = notations.map(::parse)

    @Test
    fun `straight flush beats four of a kind`() {
        val sf = HandEvaluator.scoreFive(hand("9S", "8S", "7S", "6S", "5S"))
        val quads = HandEvaluator.scoreFive(hand("AS", "AC", "AH", "AD", "KS"))
        assertEquals(Category.STRAIGHT_FLUSH, sf.category)
        assertEquals(Category.FOUR_OF_A_KIND, quads.category)
        assertTrue(sf > quads)
    }

    @Test
    fun `royal flush is just an ace-high straight flush`() {
        val royal = HandEvaluator.scoreFive(hand("AS", "KS", "QS", "JS", "TS"))
        assertEquals(Category.STRAIGHT_FLUSH, royal.category)
        assertEquals(listOf(Rank.ACE.value), royal.tiebreakers)
    }

    @Test
    fun `wheel A-2-3-4-5 is a five-high straight not ace-high`() {
        val wheel = HandEvaluator.scoreFive(hand("AS", "2H", "3D", "4C", "5S"))
        assertEquals(Category.STRAIGHT, wheel.category)
        assertEquals(listOf(5), wheel.tiebreakers)

        val sixHigh = HandEvaluator.scoreFive(hand("2S", "3H", "4D", "5C", "6S"))
        assertTrue(sixHigh > wheel, "6-high straight beats the wheel")
    }

    @Test
    fun `steel wheel is a 5-high straight flush, weaker than 6-high straight flush`() {
        val steelWheel = HandEvaluator.scoreFive(hand("AS", "2S", "3S", "4S", "5S"))
        assertEquals(Category.STRAIGHT_FLUSH, steelWheel.category)
        assertEquals(listOf(5), steelWheel.tiebreakers)

        val sixHighSF = HandEvaluator.scoreFive(hand("2S", "3S", "4S", "5S", "6S"))
        assertTrue(sixHighSF > steelWheel)
    }

    @Test
    fun `four of a kind tiebreak by quad rank then kicker`() {
        val fkQueens = HandEvaluator.scoreFive(hand("QS", "QC", "QH", "QD", "2S"))
        val fkJacksAceKicker = HandEvaluator.scoreFive(hand("JS", "JC", "JH", "JD", "AS"))
        assertTrue(fkQueens > fkJacksAceKicker, "quad queens beat quad jacks regardless of kicker")

        val fkJacksKingKicker = HandEvaluator.scoreFive(hand("JS", "JC", "JH", "JD", "KS"))
        assertTrue(fkJacksAceKicker > fkJacksKingKicker, "ace kicker beats king kicker on equal quads")
    }

    @Test
    fun `full house compared by trips first then pair`() {
        val aoeKings = HandEvaluator.scoreFive(hand("AS", "AC", "AH", "KD", "KS")) // aces full of kings
        val kingsOverAces = HandEvaluator.scoreFive(hand("KS", "KC", "KH", "AD", "AS")) // kings full of aces
        assertTrue(aoeKings > kingsOverAces)
    }

    @Test
    fun `flush vs straight - flush wins`() {
        val straight = HandEvaluator.scoreFive(hand("9S", "8H", "7D", "6C", "5S"))
        val flushSeven = HandEvaluator.scoreFive(hand("7H", "5H", "3H", "2H", "9H"))
        assertTrue(flushSeven > straight)
    }

    @Test
    fun `flush tiebreakers run all five ranks descending`() {
        val flushAce = HandEvaluator.scoreFive(hand("AH", "TH", "9H", "5H", "2H"))
        val flushKing = HandEvaluator.scoreFive(hand("KH", "QH", "JH", "9H", "5H"))
        assertTrue(flushAce > flushKing, "ace-high flush beats king-high flush")

        val flushAceTen = HandEvaluator.scoreFive(hand("AH", "TH", "9H", "5H", "2H"))
        val flushAceNine = HandEvaluator.scoreFive(hand("AH", "9H", "8H", "5H", "2H"))
        assertTrue(flushAceTen > flushAceNine, "second-highest card breaks the tie")
    }

    @Test
    fun `three of a kind tiebreak by trip rank then kickers`() {
        val tripsAces = HandEvaluator.scoreFive(hand("AS", "AC", "AH", "5D", "2S"))
        val tripsKings = HandEvaluator.scoreFive(hand("KS", "KC", "KH", "AD", "QS"))
        assertTrue(tripsAces > tripsKings)

        val tripsAcesAceQueen = HandEvaluator.scoreFive(hand("AS", "AC", "AH", "QD", "JS"))
        assertTrue(tripsAcesAceQueen > tripsAces, "QJ kickers beat 52 kickers on equal trips")
    }

    @Test
    fun `two pair tiebreak by high pair, low pair, then kicker`() {
        val acesAndKings = HandEvaluator.scoreFive(hand("AS", "AC", "KH", "KD", "2S"))
        val acesAndQueens = HandEvaluator.scoreFive(hand("AS", "AC", "QH", "QD", "JS"))
        assertTrue(acesAndKings > acesAndQueens)

        val acesAndKingsHigherKicker = HandEvaluator.scoreFive(hand("AS", "AC", "KH", "KD", "QS"))
        assertTrue(acesAndKingsHigherKicker > acesAndKings, "kicker breaks tied two pair")
    }

    @Test
    fun `pair tiebreak by pair rank then three kickers`() {
        val pairAces = HandEvaluator.scoreFive(hand("AS", "AC", "QH", "JD", "2S"))
        val pairKings = HandEvaluator.scoreFive(hand("KS", "KC", "AH", "QD", "JS"))
        assertTrue(pairAces > pairKings)
    }

    @Test
    fun `bestHand picks the strongest 5-from-7`() {
        // Hole AH AD plus board KH QH JH TH 2C → royal flush!
        val best = HandEvaluator.bestHand(
            holeCards = hand("AH", "AD"),
            board = hand("KH", "QH", "JH", "TH", "2C")
        )
        assertEquals(Category.STRAIGHT_FLUSH, best.category)
        assertEquals(listOf(Rank.ACE.value), best.tiebreakers)
    }

    @Test
    fun `bestHand prefers a flush over a pair when both available`() {
        // Hole 5H 9H, board AH KH 2H 7C 7D → 7s pair OR ace-high flush.
        val best = HandEvaluator.bestHand(
            holeCards = hand("5H", "9H"),
            board = hand("AH", "KH", "2H", "7C", "7D")
        )
        assertEquals(Category.FLUSH, best.category)
    }

    @Test
    fun `equal hands compare equal regardless of suit`() {
        val a = HandEvaluator.scoreFive(hand("AS", "KS", "QS", "JS", "9S"))
        val b = HandEvaluator.scoreFive(hand("AH", "KH", "QH", "JH", "9H"))
        // Both ace-high flushes with the same ranks — exactly equal.
        assertEquals(0, a.compareTo(b))
    }
}
