package database.blackjack

import database.card.Card
import database.card.Deck
import database.card.Rank
import database.card.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BlackjackTest {

    private fun c(rank: Rank, suit: Suit = Suit.SPADES) = Card(rank, suit)

    @Test
    fun `dealStartingHands draws two cards each from a single deck`() {
        val deck = Deck(Random(42))
        val initialSize = deck.size
        val deal = Blackjack().dealStartingHands(deck)
        assertEquals(2, deal.player.size)
        assertEquals(2, deal.dealer.size)
        assertEquals(initialSize - 4, deck.size)
    }

    @Test
    fun `hit appends one card to the hand and reduces the deck`() {
        val bj = Blackjack()
        val deck = Deck(Random(7))
        val hand = mutableListOf(c(Rank.TWO))
        val before = deck.size
        bj.hit(hand, deck)
        assertEquals(2, hand.size)
        assertEquals(before - 1, deck.size)
    }

    @Test
    fun `playOutDealer hits below 17 and stands at 17 or above`() {
        val bj = Blackjack()
        // Force dealer to start at 12 then draw a 5 (=17, must stand).
        val dealer = mutableListOf(c(Rank.SEVEN), c(Rank.FIVE))
        bj.playOutDealer(dealer, mockDeck(c(Rank.FIVE)))
        assertEquals(12 + 0, bestTotal(mutableListOf(c(Rank.SEVEN), c(Rank.FIVE)))) // sanity
        // The dealer started at 12 (7+5), drew the queued 5, now at 17 → stands.
        assertEquals(17, bestTotal(dealer))
        assertEquals(3, dealer.size)
    }

    @Test
    fun `playOutDealer stands on soft 17 (S17 rule)`() {
        val bj = Blackjack()
        val dealer = mutableListOf(c(Rank.ACE), c(Rank.SIX)) // soft 17
        // Provide a card the dealer would only draw if they hit on soft 17.
        bj.playOutDealer(dealer, mockDeck(c(Rank.FIVE)))
        // S17: dealer should NOT have drawn.
        assertEquals(2, dealer.size)
        assertEquals(17, bestTotal(dealer))
    }

    @Test
    fun `playOutDealer keeps drawing until at least 17`() {
        val bj = Blackjack()
        val dealer = mutableListOf(c(Rank.TWO), c(Rank.THREE)) // 5
        // Queue up draws of 4, 4, 6 → 5+4=9, +4=13, +6=19 → stand.
        bj.playOutDealer(dealer, mockDeck(c(Rank.FOUR), c(Rank.FOUR, Suit.HEARTS), c(Rank.SIX)))
        assertEquals(19, bestTotal(dealer))
        assertEquals(5, dealer.size)
    }

    @Test
    fun `evaluate returns PLAYER_BUST when player hand exceeds 21 even if dealer also busts`() {
        val bj = Blackjack()
        val player = listOf(c(Rank.KING), c(Rank.QUEEN), c(Rank.TWO)) // 22
        val dealer = listOf(c(Rank.KING), c(Rank.QUEEN), c(Rank.FIVE)) // 25
        assertEquals(Blackjack.Result.PLAYER_BUST, bj.evaluate(player, dealer))
    }

    @Test
    fun `evaluate returns PUSH for two-card 21 vs two-card 21`() {
        val bj = Blackjack()
        val player = listOf(c(Rank.ACE), c(Rank.KING))
        val dealer = listOf(c(Rank.ACE, Suit.HEARTS), c(Rank.QUEEN, Suit.HEARTS))
        assertEquals(Blackjack.Result.PUSH, bj.evaluate(player, dealer))
    }

    @Test
    fun `evaluate returns PLAYER_BLACKJACK when only the player has a natural 21`() {
        val bj = Blackjack()
        val player = listOf(c(Rank.ACE), c(Rank.KING))
        val dealer = listOf(c(Rank.JACK), c(Rank.SEVEN)) // 17, not BJ
        assertEquals(Blackjack.Result.PLAYER_BLACKJACK, bj.evaluate(player, dealer))
    }

    @Test
    fun `evaluate returns DEALER_WIN when only the dealer has natural blackjack`() {
        val bj = Blackjack()
        val player = listOf(c(Rank.JACK), c(Rank.SEVEN)) // 17
        val dealer = listOf(c(Rank.ACE), c(Rank.KING))
        assertEquals(Blackjack.Result.DEALER_WIN, bj.evaluate(player, dealer))
    }

    @Test
    fun `evaluate returns PLAYER_WIN when dealer busts`() {
        val bj = Blackjack()
        val player = listOf(c(Rank.KING), c(Rank.SEVEN))
        val dealer = listOf(c(Rank.KING), c(Rank.QUEEN), c(Rank.FIVE)) // 25
        assertEquals(Blackjack.Result.PLAYER_WIN, bj.evaluate(player, dealer))
    }

    @Test
    fun `evaluate compares totals when neither blackjacks nor busts`() {
        val bj = Blackjack()
        val twenty = listOf(c(Rank.KING), c(Rank.QUEEN))
        val nineteen = listOf(c(Rank.KING, Suit.HEARTS), c(Rank.NINE))
        assertEquals(Blackjack.Result.PLAYER_WIN, bj.evaluate(twenty, nineteen))
        assertEquals(Blackjack.Result.DEALER_WIN, bj.evaluate(nineteen, twenty))
        assertEquals(Blackjack.Result.PUSH, bj.evaluate(twenty, listOf(c(Rank.JACK, Suit.HEARTS), c(Rank.QUEEN, Suit.HEARTS))))
    }

    @Test
    fun `evaluate fromSplit suppresses the natural-blackjack premium`() {
        // A two-card 21 reached on a split hand counts as a regular win
        // (1:1) — never a natural blackjack (3:2). Standard rule: split
        // aces drawing a ten-value land at 21 but pay 1:1.
        val bj = Blackjack()
        val player = listOf(c(Rank.ACE), c(Rank.KING))            // would be BJ in the normal rule
        val dealer = listOf(c(Rank.NINE), c(Rank.SEVEN))          // 16, dealer plays out
        assertEquals(Blackjack.Result.PLAYER_BLACKJACK, bj.evaluate(player, dealer))
        assertEquals(Blackjack.Result.PLAYER_WIN, bj.evaluate(player, dealer, fromSplit = true))
    }

    @Test
    fun `multiplier matches the documented payout schedule`() {
        val bj = Blackjack()
        assertEquals(2.5, bj.multiplier(Blackjack.Result.PLAYER_BLACKJACK))
        assertEquals(2.0, bj.multiplier(Blackjack.Result.PLAYER_WIN))
        assertEquals(1.0, bj.multiplier(Blackjack.Result.PUSH))
        assertEquals(0.0, bj.multiplier(Blackjack.Result.DEALER_WIN))
        assertEquals(0.0, bj.multiplier(Blackjack.Result.PLAYER_BUST))
    }

    @Test
    fun `RTP across 50k seeded basic-strategy hands stays close to break-even`() {
        // Simple basic-strategy approximation: stand on 17+, hit on <12,
        // and on 12-16 hit only when the dealer's up card is 7+. Doubles
        // and splits are out of scope for the approximation. The exact
        // figure depends on which crude policy you use; we just want to
        // verify RTP isn't catastrophically off in either direction —
        // i.e. the engine isn't free-money or a guaranteed grind.
        val bj = Blackjack(Random(2026))
        val stake = 100L
        var totalWagered = 0L
        var totalReturned = 0.0
        repeat(50_000) {
            val deck = bj.newDeck()
            val deal = bj.dealStartingHands(deck)
            // Player plays out by basic-strategy approximation.
            val dealerUp = deal.dealer.first()
            val dealerUpValue = if (dealerUp.rank == Rank.ACE) 11 else dealerUp.blackjackValues().first()
            while (!isBust(deal.player) && !isBlackjack(deal.player)) {
                val total = bestTotal(deal.player)
                val shouldHit = when {
                    total < 12 -> true
                    total < 17 -> dealerUpValue >= 7
                    else -> false
                }
                if (!shouldHit) break
                deal.player.add(deck.deal())
            }
            if (!isBust(deal.player)) bj.playOutDealer(deal.dealer, deck)
            val result = bj.evaluate(deal.player, deal.dealer)
            val payout = (stake * bj.multiplier(result)).toLong()
            totalWagered += stake
            totalReturned += payout.toDouble()
        }
        val rtp = totalReturned / totalWagered.toDouble()
        // Crude basic strategy plus single-deck S17 BJ-3:2 rules sit
        // around 0.95-1.02 in practice; assert a generous tolerance so
        // the test isn't flaky on different RNG seeds while still
        // catching a regression that flipped the maths upside-down.
        assertTrue(rtp in 0.85..1.10, "expected RTP in 0.85..1.10 but saw $rtp")
    }

    @Test
    fun `newDeck shuffles deterministically per engine RNG`() {
        val a = Blackjack(Random(123)).newDeck().deal(5)
        val b = Blackjack(Random(123)).newDeck().deal(5)
        assertEquals(a, b)
        val c = Blackjack(Random(456)).newDeck().deal(5)
        assertNotEquals(a, c)
    }

    /**
     * Test helper — a Deck whose dealt cards are the supplied [seq],
     * in order. Calls beyond the sequence will throw via the underlying
     * ArrayDeque, which is exactly what we want to assert that the
     * dealer doesn't draw more than expected.
     */
    private fun mockDeck(vararg seq: Card): Deck {
        // Ugly but effective: build a real Deck and reflectively replace
        // its internal ArrayDeque. Not allowed — use the real Deck and
        // accept that the next cards drawn are random. Actually, the
        // `Deck` API only exposes the ArrayDeque indirectly, so instead
        // wrap as a thin adapter that satisfies Deck.deal() behaviour.
        // Simpler path: produce a real Deck whose first N cards we
        // control by using a custom Random that returns the cards we
        // want. That's hard since shuffle uses Fisher-Yates.
        //
        // Easiest workaround: extend Deck with our own subclass that
        // overrides deal(). But Deck.deal() is `final` (no `open`).
        //
        // Pragmatic compromise — build a real Deck and pre-burn cards
        // until our target seq is on top. The seq we supply must be
        // *contained in some shuffle*. Far too fragile.
        //
        // Final approach: bypass Deck entirely. Construct via a small
        // local stand-in by serialising the cards into the head of a
        // real Deck — we do that by reflection on the private `cards`
        // field. The Deck class is small and stable.
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
