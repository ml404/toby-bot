package common.poker

import common.card.Card
import common.card.Deck
import common.card.Rank
import common.card.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CasinoHoldemTest {

    private val game = CasinoHoldem()

    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    // --- dealAll shape ---

    @Test
    fun `dealAll peels two hole cards each, a three-card flop, a turn, and a river`() {
        val deck = Deck(Random(42))
        val deal = game.dealAll(deck)

        assertEquals(2, deal.playerHole.size)
        assertEquals(2, deal.dealerHole.size)
        assertEquals(3, deal.flop.size)
        // Nine cards consumed total (52 - 9 = 43 left in the deck).
        assertEquals(43, deck.size)

        val all = deal.playerHole + deal.dealerHole + deal.flop + deal.turn + deal.river
        // No duplicates across hands + community.
        assertEquals(all.size, all.toSet().size)
    }

    // --- dealer qualification (Pair of Fours or better) ---

    @Test
    fun `dealer qualifies on pair of fours and on any pair stronger than fours`() {
        val pairOfFours = HandEvaluator.HandRank(
            HandEvaluator.Category.PAIR, listOf(Rank.FOUR.value, 13, 12, 11)
        )
        val pairOfFives = HandEvaluator.HandRank(
            HandEvaluator.Category.PAIR, listOf(Rank.FIVE.value, 13, 12, 11)
        )
        val pairOfAces = HandEvaluator.HandRank(
            HandEvaluator.Category.PAIR, listOf(Rank.ACE.value, 13, 12, 11)
        )

        assertTrue(game.dealerQualifies(pairOfFours))
        assertTrue(game.dealerQualifies(pairOfFives))
        assertTrue(game.dealerQualifies(pairOfAces))
    }

    @Test
    fun `dealer does not qualify on pair of threes or below, or on bare high card`() {
        val pairOfThrees = HandEvaluator.HandRank(
            HandEvaluator.Category.PAIR, listOf(Rank.THREE.value, 13, 12, 11)
        )
        val pairOfTwos = HandEvaluator.HandRank(
            HandEvaluator.Category.PAIR, listOf(Rank.TWO.value, 13, 12, 11)
        )
        val highCard = HandEvaluator.HandRank(
            HandEvaluator.Category.HIGH_CARD, listOf(14, 13, 12, 11, 9)
        )

        assertFalse(game.dealerQualifies(pairOfThrees))
        assertFalse(game.dealerQualifies(pairOfTwos))
        assertFalse(game.dealerQualifies(highCard))
    }

    @Test
    fun `dealer qualifies on every category above pair`() {
        val twoPair = HandEvaluator.HandRank(
            HandEvaluator.Category.TWO_PAIR, listOf(Rank.TWO.value, Rank.THREE.value, 5)
        )
        val trips = HandEvaluator.HandRank(
            HandEvaluator.Category.THREE_OF_A_KIND, listOf(Rank.TWO.value, 5, 4)
        )
        val straight = HandEvaluator.HandRank(HandEvaluator.Category.STRAIGHT, listOf(5))
        val flush = HandEvaluator.HandRank(HandEvaluator.Category.FLUSH, listOf(8, 6, 5, 3, 2))
        val fullHouse = HandEvaluator.HandRank(HandEvaluator.Category.FULL_HOUSE, listOf(2, 3))
        val quads = HandEvaluator.HandRank(HandEvaluator.Category.FOUR_OF_A_KIND, listOf(2, 5))
        val sf = HandEvaluator.HandRank(HandEvaluator.Category.STRAIGHT_FLUSH, listOf(5))

        for (rank in listOf(twoPair, trips, straight, flush, fullHouse, quads, sf)) {
            assertTrue(game.dealerQualifies(rank), "expected qualify: $rank")
        }
    }

    // --- royal flush detection ---

    @Test
    fun `royal flush is a straight flush whose top tiebreaker is the ace value`() {
        val royal = HandEvaluator.HandRank(HandEvaluator.Category.STRAIGHT_FLUSH, listOf(Rank.ACE.value))
        val sfTo10 = HandEvaluator.HandRank(HandEvaluator.Category.STRAIGHT_FLUSH, listOf(Rank.TEN.value))
        val plainStraight = HandEvaluator.HandRank(HandEvaluator.Category.STRAIGHT, listOf(Rank.ACE.value))

        assertTrue(game.isRoyalFlush(royal))
        assertFalse(game.isRoyalFlush(sfTo10))
        assertFalse(game.isRoyalFlush(plainStraight))
    }

    // --- ante / call multipliers ---

    @Test
    fun `ante multiplier returns even-money on win, refund on push, zero on loss`() {
        assertEquals(2.0, game.anteMultiplier(CasinoHoldem.AnteResult.WIN), 1e-9)
        assertEquals(1.0, game.anteMultiplier(CasinoHoldem.AnteResult.PUSH), 1e-9)
        assertEquals(0.0, game.anteMultiplier(CasinoHoldem.AnteResult.LOSE), 1e-9)
    }

    @Test
    fun `call multiplier matches the standard Casino Hold'em paytable`() {
        // Returns are bet-inclusive (e.g. 1:1 → 2.0, 100:1 → 101.0).
        assertEquals(101.0, game.callMultiplier(CasinoHoldem.CallResult.WIN_ROYAL_FLUSH), 1e-9)
        assertEquals(21.0, game.callMultiplier(CasinoHoldem.CallResult.WIN_STRAIGHT_FLUSH), 1e-9)
        assertEquals(11.0, game.callMultiplier(CasinoHoldem.CallResult.WIN_QUADS), 1e-9)
        assertEquals(4.0, game.callMultiplier(CasinoHoldem.CallResult.WIN_FULL_HOUSE), 1e-9)
        assertEquals(3.0, game.callMultiplier(CasinoHoldem.CallResult.WIN_FLUSH), 1e-9)
        assertEquals(2.0, game.callMultiplier(CasinoHoldem.CallResult.WIN_STRAIGHT), 1e-9)
        assertEquals(2.0, game.callMultiplier(CasinoHoldem.CallResult.WIN_OTHER), 1e-9)
        assertEquals(1.0, game.callMultiplier(CasinoHoldem.CallResult.PUSH), 1e-9)
        assertEquals(0.0, game.callMultiplier(CasinoHoldem.CallResult.LOSE), 1e-9)
        assertEquals(0.0, game.callMultiplier(CasinoHoldem.CallResult.FOLDED), 1e-9)
    }

    // --- resolve() integration ---

    @Test
    fun `resolve returns ante WIN, call PUSH when dealer fails to qualify`() {
        // Player: pair of kings; dealer: high card only; board: blanks that
        // give the dealer nothing.
        val playerHole = listOf(c(Rank.KING, Suit.SPADES), c(Rank.KING, Suit.HEARTS))
        val dealerHole = listOf(c(Rank.SEVEN, Suit.CLUBS), c(Rank.TWO, Suit.DIAMONDS))
        val board = listOf(
            c(Rank.JACK, Suit.SPADES),
            c(Rank.NINE, Suit.HEARTS),
            c(Rank.FIVE, Suit.CLUBS),
            c(Rank.THREE, Suit.DIAMONDS),
            c(Rank.SIX, Suit.HEARTS),
        )

        val res = game.resolve(playerHole, dealerHole, board)

        assertFalse(res.dealerQualified)
        assertEquals(CasinoHoldem.AnteResult.WIN, res.anteResult)
        assertEquals(CasinoHoldem.CallResult.PUSH, res.callResult)
    }

    @Test
    fun `resolve awards player a flush win when both qualify and player out-ranks dealer`() {
        // Player: J-10 of spades. Board: A♠ K♠ 5♠ 4♣ 2♠ — player has ace-high spade flush.
        // Dealer: K♦ Q♣ — pair of kings (qualifies), but flush beats pair.
        val playerHole = listOf(c(Rank.JACK, Suit.SPADES), c(Rank.TEN, Suit.SPADES))
        val dealerHole = listOf(c(Rank.KING, Suit.DIAMONDS), c(Rank.QUEEN, Suit.CLUBS))
        val board = listOf(
            c(Rank.ACE, Suit.SPADES),
            c(Rank.KING, Suit.SPADES),
            c(Rank.FIVE, Suit.SPADES),
            c(Rank.FOUR, Suit.CLUBS),
            c(Rank.TWO, Suit.SPADES),
        )

        val res = game.resolve(playerHole, dealerHole, board)

        assertTrue(res.dealerQualified)
        assertEquals(CasinoHoldem.AnteResult.WIN, res.anteResult)
        assertEquals(CasinoHoldem.CallResult.WIN_FLUSH, res.callResult)
    }

    @Test
    fun `resolve marks royal flush when player has T-J-Q-K-A of one suit`() {
        val playerHole = listOf(c(Rank.ACE, Suit.HEARTS), c(Rank.KING, Suit.HEARTS))
        val dealerHole = listOf(c(Rank.SEVEN, Suit.CLUBS), c(Rank.SEVEN, Suit.DIAMONDS))
        val board = listOf(
            c(Rank.QUEEN, Suit.HEARTS),
            c(Rank.JACK, Suit.HEARTS),
            c(Rank.TEN, Suit.HEARTS),
            c(Rank.TWO, Suit.CLUBS),
            c(Rank.THREE, Suit.SPADES),
        )

        val res = game.resolve(playerHole, dealerHole, board)

        assertTrue(res.dealerQualified)
        assertEquals(CasinoHoldem.AnteResult.WIN, res.anteResult)
        assertEquals(CasinoHoldem.CallResult.WIN_ROYAL_FLUSH, res.callResult)
    }

    @Test
    fun `resolve loses both legs when dealer qualifies and out-ranks the player`() {
        // Player: pair of fives; dealer: pair of aces. Both qualify.
        val playerHole = listOf(c(Rank.FIVE, Suit.SPADES), c(Rank.FIVE, Suit.HEARTS))
        val dealerHole = listOf(c(Rank.ACE, Suit.CLUBS), c(Rank.ACE, Suit.DIAMONDS))
        val board = listOf(
            c(Rank.JACK, Suit.SPADES),
            c(Rank.NINE, Suit.HEARTS),
            c(Rank.SEVEN, Suit.CLUBS),
            c(Rank.THREE, Suit.DIAMONDS),
            c(Rank.TWO, Suit.HEARTS),
        )

        val res = game.resolve(playerHole, dealerHole, board)

        assertTrue(res.dealerQualified)
        assertEquals(CasinoHoldem.AnteResult.LOSE, res.anteResult)
        assertEquals(CasinoHoldem.CallResult.LOSE, res.callResult)
    }

    @Test
    fun `resolve pushes both legs when player and dealer share the same best hand`() {
        // Both end up using the board's straight (T-J-Q-K-A) as their best 5.
        val playerHole = listOf(c(Rank.TWO, Suit.SPADES), c(Rank.THREE, Suit.HEARTS))
        val dealerHole = listOf(c(Rank.FOUR, Suit.CLUBS), c(Rank.FIVE, Suit.DIAMONDS))
        val board = listOf(
            c(Rank.TEN, Suit.HEARTS),
            c(Rank.JACK, Suit.SPADES),
            c(Rank.QUEEN, Suit.CLUBS),
            c(Rank.KING, Suit.DIAMONDS),
            c(Rank.ACE, Suit.SPADES),
        )

        val res = game.resolve(playerHole, dealerHole, board)

        assertTrue(res.dealerQualified)
        assertEquals(CasinoHoldem.AnteResult.PUSH, res.anteResult)
        assertEquals(CasinoHoldem.CallResult.PUSH, res.callResult)
    }
}
