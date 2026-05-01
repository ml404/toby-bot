package database.poker

import database.card.Card
import database.card.Rank
import database.card.Suit
import database.poker.PokerTable.Phase
import database.poker.PokerTable.SeatStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Multi-tier side-pot resolution. Drives [PokerEngine.resolveHand]
 * directly with hand-crafted post-betting table states so each
 * scenario tests one specific tier-construction or eligibility rule
 * in isolation.
 *
 * The test seeds known hole cards into each seat by reaching into the
 * mutable [PokerTable.Seat.holeCards] field, so the winners are
 * deterministic without needing to seed a deck and play through a
 * real hand. The board is also fixed.
 */
class PokerEngineSidePotTest {

    private val now: Instant = Instant.parse("2026-04-10T10:00:00Z")
    private val rakeZero = 0.0
    private val rake = 0.05

    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)

    private fun makeTable(): PokerTable = PokerTable(
        id = 1L,
        guildId = 42L,
        hostDiscordId = 1L,
        minBuyIn = 100L,
        maxBuyIn = 5000L,
        smallBlind = 5L,
        bigBlind = 10L,
        smallBet = 10L,
        bigBet = 20L,
        maxRaisesPerStreet = 4,
        maxSeats = 6,
    )

    /**
     * Build a table at the "river complete, ready to settle" point.
     * Each entry in [seats] gives `(discordId, chipsRemaining, totalCommitted, status, holeCards)`.
     * Board is fixed across tests so winners are deterministic from hole cards.
     */
    private fun showdown(
        seats: List<SeatSetup>,
        board: List<Card> = STD_BOARD
    ): PokerTable {
        val table = makeTable()
        table.phase = Phase.RIVER
        table.community.addAll(board)
        table.pot = seats.sumOf { it.totalCommitted }
        for (s in seats) {
            val seat = PokerTable.Seat(discordId = s.discordId, chips = s.chips)
            seat.totalCommittedThisHand = s.totalCommitted
            seat.committedThisRound = 0L
            seat.status = s.status
            seat.holeCards = s.holeCards
            table.seats.add(seat)
        }
        return table
    }

    private data class SeatSetup(
        val discordId: Long,
        val chips: Long,
        val totalCommitted: Long,
        val status: SeatStatus,
        val holeCards: List<Card>
    )

    companion object {
        // Plain rainbow board with no straight/flush draw on it. Each
        // seat's "best hand" is determined by their hole cards alone.
        private val STD_BOARD = listOf(
            Card(Rank.TWO, Suit.CLUBS),
            Card(Rank.THREE, Suit.DIAMONDS),
            Card(Rank.SEVEN, Suit.HEARTS),
            Card(Rank.NINE, Suit.SPADES),
            Card(Rank.JACK, Suit.CLUBS)
        )
    }

    // High-card-only hole cards that play exactly to the listed rank
    // when paired with STD_BOARD. e.g. ACE_HOLE makes "ace high".
    private val ACE_HOLE = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.FOUR, Suit.CLUBS))
    private val KING_HOLE = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.FIVE, Suit.CLUBS))
    private val QUEEN_HOLE = listOf(Card(Rank.QUEEN, Suit.SPADES), Card(Rank.SIX, Suit.CLUBS))

    // Pocket pairs. With STD_BOARD (no T/J/K duplicates of these), each
    // plays to its own pocket-pair rank; relative strength is just the
    // pair rank. POCKET_TENS and ALT_TENS make the SAME hand (pair of
    // tens with identical board kickers) so they chop at showdown.
    private val POCKET_TENS = listOf(Card(Rank.TEN, Suit.SPADES), Card(Rank.TEN, Suit.HEARTS))
    private val ALT_TENS = listOf(Card(Rank.TEN, Suit.CLUBS), Card(Rank.TEN, Suit.DIAMONDS))
    // Pair of jacks via the board jack — beats every pocket pair below
    // jacks because the kickers come off the board identically.
    private val JACK_PAIR = listOf(Card(Rank.JACK, Suit.SPADES), Card(Rank.FIVE, Suit.HEARTS))

    @Test
    fun `single contender (everyone else folded) takes the whole pot`() {
        // No all-in tiers needed. One seat survived the betting round.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 60L, status = SeatStatus.ACTIVE, holeCards = ACE_HOLE),
                SeatSetup(2L, chips = 0L, totalCommitted = 30L, status = SeatStatus.FOLDED, holeCards = KING_HOLE),
                SeatSetup(3L, chips = 0L, totalCommitted = 60L, status = SeatStatus.FOLDED, holeCards = QUEEN_HOLE),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rakeZero, now = now)

        assertEquals(150L, result.pot)
        assertEquals(0L, result.rake)
        assertEquals(listOf(1L), result.winners)
        assertEquals(150L, result.payoutByDiscordId[1L])
        assertEquals(1, result.pots.size, "single contender → single tier")
        assertEquals(150L, result.pots[0].amount)
        assertTrue(result.refundedByDiscordId.isEmpty(), "fully contested, nothing to refund")
        assertTrue(result.revealedHoleCards.isEmpty(), "no showdown, no reveal")
    }

    @Test
    fun `3-way different all-in amounts produces main + side pot`() {
        // A all-in 50, B all-in 200, C all-in 200. A has the strongest
        // hand (pair of jacks via board) and wins the main pot; B has
        // pocket tens and wins the side pot over C's king-high.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = JACK_PAIR),
                SeatSetup(2L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ALL_IN, holeCards = POCKET_TENS),
                SeatSetup(3L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ACTIVE, holeCards = KING_HOLE),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rakeZero, now = now)

        // Main pot: 50 × 3 = 150, eligible {1,2,3}.
        // Side pot: 150 × 2 = 300, eligible {2,3}.
        assertEquals(450L, result.pot)
        assertEquals(2, result.pots.size)
        assertEquals(50L, result.pots[0].cap)
        assertEquals(150L, result.pots[0].amount)
        assertEquals(setOf(1L, 2L, 3L), result.pots[0].eligibleDiscordIds.toSet())
        assertEquals(200L, result.pots[1].cap)
        assertEquals(300L, result.pots[1].amount)
        assertEquals(setOf(2L, 3L), result.pots[1].eligibleDiscordIds.toSet())

        // A has pair of jacks — wins main pot. B has pocket TT — wins
        // side pot over C's king-high.
        assertEquals(listOf(1L), result.pots[0].winners)
        assertEquals(listOf(2L), result.pots[1].winners)
        assertEquals(150L, result.payoutByDiscordId[1L])
        assertEquals(300L, result.payoutByDiscordId[2L])
        // C committed 200 but won nothing.
        assertEquals(null, result.payoutByDiscordId[3L])
    }

    @Test
    fun `winner of side pot is not the winner of main pot`() {
        // Same shape as above but: A has the strongest hand (pair of jacks),
        // B has medium (pocket tens — pair of tens), C has weakest (king-high).
        // Main pot eligibles {A, B, C} → A wins. Side pot eligibles {B, C} → B wins.
        // This proves eligibility filters the winner pool, not just hand strength.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = JACK_PAIR),
                SeatSetup(2L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ALL_IN, holeCards = POCKET_TENS),
                SeatSetup(3L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ACTIVE, holeCards = KING_HOLE),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rakeZero, now = now)

        assertEquals(listOf(1L), result.pots[0].winners, "A wins main pot")
        assertEquals(listOf(2L), result.pots[1].winners, "B wins side pot, NOT A")
        assertEquals(150L, result.payoutByDiscordId[1L])
        assertEquals(300L, result.payoutByDiscordId[2L])
        assertNotEquals(result.pots[0].winners, result.pots[1].winners)
    }

    @Test
    fun `4-way all-in at two distinct tiers builds two side pots`() {
        // A all-in 50, B all-in 50, C all-in 200, D all-in 200.
        // Tier 1 cap 50: 4 × 50 = 200, eligible {A,B,C,D}.
        // Tier 2 cap 200: 2 × 150 = 300, eligible {C,D}.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = JACK_PAIR),
                SeatSetup(2L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = QUEEN_HOLE),
                SeatSetup(3L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ALL_IN, holeCards = POCKET_TENS),
                SeatSetup(4L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ACTIVE, holeCards = KING_HOLE),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rakeZero, now = now)

        assertEquals(500L, result.pot)
        assertEquals(2, result.pots.size)
        assertEquals(200L, result.pots[0].amount)
        assertEquals(setOf(1L, 2L, 3L, 4L), result.pots[0].eligibleDiscordIds.toSet())
        assertEquals(300L, result.pots[1].amount)
        assertEquals(setOf(3L, 4L), result.pots[1].eligibleDiscordIds.toSet())
        assertEquals(listOf(1L), result.pots[0].winners, "A's pair of jacks wins tier 1")
        assertEquals(listOf(3L), result.pots[1].winners, "C's pocket TT wins tier 2 over D's K-high")
    }

    @Test
    fun `distinct winner takes one tier, tied hands chop the other`() {
        // A all-in 50 with the strongest hand (pair of jacks). B and C
        // both committed 200 with identical pocket tens (different
        // suits, same hand strength against the board).
        // Tier 1 (50): all three eligible — A wins outright.
        // Tier 2 (200): only B and C eligible — they chop, splitting
        // the side pot evenly.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = JACK_PAIR),
                SeatSetup(2L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ALL_IN, holeCards = POCKET_TENS),
                SeatSetup(3L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ACTIVE, holeCards = ALT_TENS),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rakeZero, now = now)

        assertEquals(2, result.pots.size)
        // Main pot 150 — A wins outright with pair of jacks.
        assertEquals(listOf(1L), result.pots[0].winners)
        assertEquals(150L, result.pots[0].payoutByDiscordId[1L])
        // Side pot 300 chopped between B and C → 150 each.
        assertEquals(setOf(2L, 3L), result.pots[1].winners.toSet())
        assertEquals(150L, result.pots[1].payoutByDiscordId[2L])
        assertEquals(150L, result.pots[1].payoutByDiscordId[3L])
    }

    @Test
    fun `over-commit beyond highest contender is refunded uncontested`() {
        // A all-in 50 (the only contender at the high end), B over-bet 200.
        // No one can match B's commitment beyond 50, so B's 150 excess is
        // refunded directly — no rake on the refund.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = ACE_HOLE),
                SeatSetup(2L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ACTIVE, holeCards = POCKET_TENS),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rakeZero, now = now)

        // Single contested tier of 100 (50 each), B's 150 excess returned.
        assertEquals(1, result.pots.size, "no side pot when only one tier is contested")
        assertEquals(100L, result.pots[0].amount)
        assertEquals(150L, result.refundedByDiscordId[2L])
        assertEquals(listOf(2L), result.pots[0].winners, "B's pocket tens beats A's ace-high")
        // B's chips are credited the refund in-memory.
        val bSeat = table.seats.first { it.discordId == 2L }
        assertEquals(150L + 100L, bSeat.chips, "refund + winning tier credited to chips")
    }

    @Test
    fun `chip total is conserved across a 3-way all-in resolution (with rake)`() {
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = ACE_HOLE),
                SeatSetup(2L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ALL_IN, holeCards = POCKET_TENS),
                SeatSetup(3L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ACTIVE, holeCards = KING_HOLE),
            )
        )
        val totalCommitted = table.pot

        val result = PokerEngine.resolveHand(table, rakeRate = rake, now = now)

        val chipsAfter = table.seats.sumOf { it.chips }
        assertEquals(totalCommitted - result.rake, chipsAfter,
            "every chip lands somewhere: payouts + refunds = totalCommitted - rake")
        assertEquals(result.payoutByDiscordId.values.sum() + result.refundedByDiscordId.values.sum(),
            chipsAfter)
    }

    @Test
    fun `rake comes off smallest pot first so over-tier players keep more`() {
        // A all-in 50, B all-in 50, C 1000 (over-commit will refund 950
        // of C's stake since no contender matched above 50).
        // Single contested tier of 150. 5% rake = 7. Tier amount = 143.
        // C's 950 over-commit returns unraked.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,   status = SeatStatus.ALL_IN, holeCards = ACE_HOLE),
                SeatSetup(2L, chips = 0L, totalCommitted = 50L,   status = SeatStatus.ALL_IN, holeCards = QUEEN_HOLE),
                SeatSetup(3L, chips = 0L, totalCommitted = 1000L, status = SeatStatus.ACTIVE, holeCards = KING_HOLE),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rake, now = now)

        assertEquals(1100L, result.pot, "totalCommitted across all seats")
        assertEquals(7L, result.rake, "5% of contested 150")
        assertEquals(950L, result.refundedByDiscordId[3L], "C's over-commit returned unraked")
        assertEquals(143L, result.pots[0].amount, "150 - 7 rake")
    }

    @Test
    fun `folded players still contribute their committed chips to the pots`() {
        // A all-in 50, B folded after committing 80, C committed 200.
        // C's effective level is capped at 80 (B's commit, the largest
        // any other seat put in), so C's 120 above 80 is uncalled and
        // refunded.
        //   Tier 1 cap 50: 50 (A) + 50 (B) + 50 (C) = 150. Eligible: A, C.
        //   Tier 2 cap 80:  0 (A) + 30 (B) + 30 (C) = 60.  Eligible: C.
        // B is folded so contributes dead money but isn't eligible to
        // win either tier despite putting chips in.
        val table = showdown(
            seats = listOf(
                SeatSetup(1L, chips = 0L, totalCommitted = 50L,  status = SeatStatus.ALL_IN, holeCards = ACE_HOLE),
                SeatSetup(2L, chips = 0L, totalCommitted = 80L,  status = SeatStatus.FOLDED, holeCards = POCKET_TENS),
                SeatSetup(3L, chips = 0L, totalCommitted = 200L, status = SeatStatus.ACTIVE, holeCards = KING_HOLE),
            )
        )

        val result = PokerEngine.resolveHand(table, rakeRate = rakeZero, now = now)

        assertEquals(2, result.pots.size)
        assertEquals(150L, result.pots[0].amount, "tier 1: 50 from each of A/B/C")
        assertEquals(setOf(1L, 3L), result.pots[0].eligibleDiscordIds.toSet(),
            "B is folded so not eligible despite contributing")
        assertEquals(listOf(1L), result.pots[0].winners, "A's ace-high beats C's K-high")
        assertEquals(60L, result.pots[1].amount, "tier 2: B's 30 + C's 30 (matched against B's dead money)")
        assertEquals(setOf(3L), result.pots[1].eligibleDiscordIds.toSet(),
            "only C is contender at the 80 cap")
        assertEquals(listOf(3L), result.pots[1].winners)
        assertEquals(120L, result.refundedByDiscordId[3L],
            "C's commitment above what anyone else could match (200 - 80 = 120) is returned")
    }
}
