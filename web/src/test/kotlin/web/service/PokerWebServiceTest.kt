package web.service

import database.poker.Card
import database.poker.PokerEngine
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import database.poker.Rank
import database.poker.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class PokerWebServiceTest {

    private lateinit var registry: PokerTableRegistry
    private lateinit var service: PokerWebService

    @BeforeEach
    fun setup() {
        registry = PokerTableRegistry(
            idleTtl = Duration.ofMinutes(5),
            sweepInterval = Duration.ofHours(1)
        )
        service = PokerWebService(registry)
    }

    private fun makeTable(seatChips: List<Pair<Long, Long>> = listOf(1L to 1000L, 2L to 1000L)): PokerTable {
        val t = registry.create(
            guildId = 42L,
            hostDiscordId = seatChips.first().first,
            minBuyIn = 100L,
            maxBuyIn = 5000L,
            smallBlind = 5L,
            bigBlind = 10L,
            smallBet = 10L,
            bigBet = 20L,
            maxRaisesPerStreet = 4,
            maxSeats = 6,
        )
        seatChips.forEach { (id, chips) ->
            t.seats.add(PokerTable.Seat(discordId = id, chips = chips))
        }
        return t
    }

    @Test
    fun `snapshot of unknown table is null`() {
        assertNull(service.snapshot(tableId = 999L, viewerDiscordId = 1L))
    }

    @Test
    fun `snapshot in lobby phase returns empty community and zero pot`() {
        val table = makeTable()
        val view = service.snapshot(table.id, viewerDiscordId = 1L)!!
        assertEquals(table.id, view.tableId)
        assertEquals("WAITING", view.phase)
        assertEquals(0L, view.pot)
        assertTrue(view.community.isEmpty())
        assertFalse(view.isMyTurn)
        assertFalse(view.canCheck)
        assertFalse(view.canCall)
        assertFalse(view.canRaise)
    }

    @Test
    fun `snapshot masks other players hole cards but reveals viewer cards`() {
        val table = makeTable()
        // Force a hand to be in flight.
        PokerEngine.startHand(table, Random(0), Instant.now())

        // Viewer is seat 0 (discordId 1).
        val viewAsSeat0 = service.snapshot(table.id, viewerDiscordId = 1L)!!
        val seat0 = viewAsSeat0.seats[0]
        val seat1 = viewAsSeat0.seats[1]

        assertEquals(2, seat0.holeCards.size, "viewer's own seat shows hole cards")
        assertTrue(seat1.holeCards.isEmpty(), "other seats are masked server-side")
        // The flat helper returns the same view as the seat slice.
        assertEquals(seat0.holeCards, viewAsSeat0.myHoleCards)

        // Now from seat 1's perspective, seat 1 should see their own and seat 0 masked.
        val viewAsSeat1 = service.snapshot(table.id, viewerDiscordId = 2L)!!
        assertTrue(viewAsSeat1.seats[0].holeCards.isEmpty())
        assertEquals(2, viewAsSeat1.seats[1].holeCards.size)
        // Seats are different cards (same deck but different positions).
        assertEquals(viewAsSeat1.seats[1].holeCards, viewAsSeat1.myHoleCards)
    }

    @Test
    fun `snapshot for a non-seated viewer returns mySeatIndex null and empty hole cards`() {
        val table = makeTable()
        PokerEngine.startHand(table, Random(0), Instant.now())
        val view = service.snapshot(table.id, viewerDiscordId = 999L)!!
        assertNull(view.mySeatIndex)
        assertTrue(view.myHoleCards.isEmpty())
        // Every other seat is also masked.
        view.seats.forEach { assertTrue(it.holeCards.isEmpty()) }
    }

    @Test
    fun `snapshot computes canCheck canCall canRaise based on the betting state`() {
        val table = makeTable()
        PokerEngine.startHand(table, Random(0), Instant.now())
        // Heads-up: dealer = SB acts first preflop with 5 owed to the BB.
        val sbDiscordId = table.seats[table.dealerIndex].discordId
        val view = service.snapshot(table.id, viewerDiscordId = sbDiscordId)!!
        assertTrue(view.isMyTurn)
        assertTrue(view.canCall, "SB owes the difference and has chips")
        assertFalse(view.canCheck, "can't check facing a bet")
        assertTrue(view.canRaise)
        assertEquals(5L, view.callAmount)
        assertEquals(15L, view.raiseAmount, "callAmount + smallBet = 5 + 10")
    }

    @Test
    fun `listGuildTables returns a sorted summary view`() {
        val a = makeTable(listOf(1L to 1000L, 2L to 1000L))
        val b = makeTable(listOf(3L to 1000L, 4L to 1000L))
        val rows = service.listGuildTables(guildId = 42L)
        assertEquals(2, rows.size)
        assertTrue(rows[0].tableId < rows[1].tableId, "sorted by tableId")
        assertEquals(a.maxSeats, rows[0].maxSeats)
        assertEquals(b.seats.size, rows[1].seats)
    }

    @Test
    fun `lastResult is projected when populated`() {
        val table = makeTable()
        // Synthesise a HandResult by hand to test projection without running a hand.
        table.lastResult = PokerTable.HandResult(
            handNumber = 1L,
            winners = listOf(1L),
            payoutByDiscordId = mapOf(1L to 95L),
            pot = 100L,
            rake = 5L,
            board = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.HEARTS)),
            revealedHoleCards = mapOf(1L to listOf(Card(Rank.QUEEN, Suit.SPADES))),
            resolvedAt = Instant.now()
        )
        val view = service.snapshot(table.id, viewerDiscordId = 1L)!!
        val lastResult = view.lastResult!!
        assertEquals(1L, lastResult.handNumber)
        assertEquals(95L, lastResult.payoutByDiscordId["1"])
        assertEquals(100L, lastResult.pot)
        assertEquals(5L, lastResult.rake)
        assertEquals(listOf("A♠", "K♥"), lastResult.board)
        assertEquals(listOf("Q♠"), lastResult.revealedHoleCards["1"])
    }
}
