package web.service

import common.card.Card
import common.card.Rank
import common.card.Suit
import common.casino.blackjack.Blackjack
import common.casino.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Additional coverage for [BlackjackWebService] — hole-card masking,
 * isMyTurn / canDouble / canSplit flags, lastResult projection,
 * listMultiTables lobby, and hand-slot (split) views.
 *
 * Uses a real [BlackjackTableRegistry] (no Spring, no DB, no network).
 */
class BlackjackWebServiceMoreTest {

    private lateinit var registry: BlackjackTableRegistry
    private lateinit var memberLookup: MemberLookupHelper
    private lateinit var service: BlackjackWebService

    private val guildId = 42L
    private val player1 = 1L
    private val player2 = 2L

    @BeforeEach
    fun setup() {
        registry = BlackjackTableRegistry(
            idleTtl = Duration.ofMinutes(30),
            sweepInterval = Duration.ofHours(1),
        )
        memberLookup = mockk(relaxed = true)
        every { memberLookup.fallbackName(any()) } answers {
            "Player ${(it.invocation.args[0] as Long).toString().takeLast(4)}"
        }
        every { memberLookup.resolveAll(any(), any()) } returns emptyMap()
        service = BlackjackWebService(registry, memberLookup)
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private fun makeTable(
        seatIds: List<Long> = listOf(player1),
        mode: BlackjackTable.Mode = BlackjackTable.Mode.MULTI,
        phase: BlackjackTable.Phase = BlackjackTable.Phase.LOBBY,
        dealerCards: List<Card> = listOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.FIVE, Suit.HEARTS)),
        actorIndex: Int = 0,
    ): BlackjackTable {
        val table = registry.create(
            guildId = guildId,
            mode = mode,
            hostDiscordId = seatIds.first(),
            ante = 100L,
            maxSeats = 6,
        )
        seatIds.forEach { id ->
            table.seats.add(
                BlackjackTable.Seat(
                    discordId = id,
                    hand = mutableListOf(Card(Rank.TEN, Suit.SPADES), Card(Rank.SIX, Suit.HEARTS)),
                    ante = 100L, stake = 100L,
                )
            )
        }
        dealerCards.forEach { table.dealer.add(it) }
        table.phase = phase
        table.actorIndex = actorIndex
        return table
    }

    // ─── hole-card masking ────────────────────────────────────────────

    @Test
    fun `snapshot masks dealer hole card during PLAYER_TURNS`() {
        val table = makeTable(phase = BlackjackTable.Phase.PLAYER_TURNS)
        // dealer has 2 cards: [NINE, FIVE] — only first should be visible
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertEquals(2, view.dealer.size)
        // First card is visible
        assertFalse(view.dealer[0] == "??")
        // Second card should be masked
        assertEquals("??", view.dealer[1])
    }

    @Test
    fun `snapshot does NOT mask dealer hole card during DEALER_TURN`() {
        val table = makeTable(phase = BlackjackTable.Phase.DEALER_TURN)
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        // Both cards should be their real values
        assertTrue(view.dealer.none { it == "??" })
    }

    @Test
    fun `snapshot does NOT mask dealer hole card during LOBBY`() {
        val table = makeTable(phase = BlackjackTable.Phase.LOBBY)
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertTrue(view.dealer.none { it == "??" })
    }

    @Test
    fun `snapshot does NOT mask when dealer has only one card`() {
        val table = registry.create(
            guildId = guildId,
            mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = player1,
            ante = 100L, maxSeats = 6,
        )
        table.seats.add(BlackjackTable.Seat(discordId = player1,
            hand = mutableListOf(Card(Rank.TEN, Suit.SPADES), Card(Rank.SIX, Suit.HEARTS)),
            ante = 100L, stake = 100L))
        table.dealer.add(Card(Rank.NINE, Suit.SPADES)) // only one card
        table.phase = BlackjackTable.Phase.PLAYER_TURNS

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        // size == 1, condition "dealer.size > 1" is false → no masking
        assertEquals(1, view.dealer.size)
        assertFalse(view.dealer[0] == "??")
    }

    // ─── dealerTotalVisible masking ───────────────────────────────────

    @Test
    fun `dealerTotalVisible uses first card only during PLAYER_TURNS with 2 cards`() {
        // Dealer: NINE(9) + FIVE(5). Full total = 14. Visible = 9.
        val table = makeTable(
            phase = BlackjackTable.Phase.PLAYER_TURNS,
            dealerCards = listOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.FIVE, Suit.HEARTS)),
        )
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertEquals(9, view.dealerTotalVisible)
    }

    @Test
    fun `dealerTotalVisible shows full total outside PLAYER_TURNS`() {
        // Dealer: NINE(9) + FIVE(5) = 14 total visible in LOBBY phase.
        val table = makeTable(
            phase = BlackjackTable.Phase.LOBBY,
            dealerCards = listOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.FIVE, Suit.HEARTS)),
        )
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertEquals(14, view.dealerTotalVisible)
    }

    // ─── mySeatIndex / isMyTurn / canDouble / canSplit ────────────────

    @Test
    fun `snapshot mySeatIndex is null when viewer is not seated`() {
        val table = makeTable(seatIds = listOf(player2), phase = BlackjackTable.Phase.LOBBY)
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertNull(view.mySeatIndex)
        assertFalse(view.isMyTurn)
        assertFalse(view.canDouble)
        assertFalse(view.canSplit)
    }

    @Test
    fun `snapshot isMyTurn is true when viewer is the active actor in PLAYER_TURNS`() {
        val table = makeTable(
            seatIds = listOf(player1),
            phase = BlackjackTable.Phase.PLAYER_TURNS,
            actorIndex = 0,
        )
        // Seat status must be ACTIVE for isMyTurn to be true
        table.seats[0].status = BlackjackTable.SeatStatus.ACTIVE

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertNotNull(view.mySeatIndex)
        assertEquals(0, view.mySeatIndex)
        assertTrue(view.isMyTurn)
    }

    @Test
    fun `snapshot isMyTurn is false when it is another player s turn`() {
        val table = makeTable(
            seatIds = listOf(player1, player2),
            phase = BlackjackTable.Phase.PLAYER_TURNS,
            actorIndex = 1, // player2's turn
        )
        table.seats[0].status = BlackjackTable.SeatStatus.ACTIVE
        table.seats[1].status = BlackjackTable.SeatStatus.ACTIVE

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertFalse(view.isMyTurn)
        assertFalse(view.canDouble)
    }

    @Test
    fun `snapshot canDouble is true when it is my turn with exactly 2 cards and not doubled`() {
        val table = makeTable(
            seatIds = listOf(player1),
            phase = BlackjackTable.Phase.PLAYER_TURNS,
            actorIndex = 0,
        )
        table.seats[0].status = BlackjackTable.SeatStatus.ACTIVE
        // seat already has 2 cards from makeTable and doubled=false by default

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertTrue(view.isMyTurn)
        assertTrue(view.canDouble)
    }

    @Test
    fun `snapshot canDouble is false when seat is already doubled`() {
        val table = makeTable(
            seatIds = listOf(player1),
            phase = BlackjackTable.Phase.PLAYER_TURNS,
            actorIndex = 0,
        )
        table.seats[0].status = BlackjackTable.SeatStatus.ACTIVE
        table.seats[0].doubled = true

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertFalse(view.canDouble)
    }

    @Test
    fun `snapshot canDouble is false when hand has more than 2 cards`() {
        val table = makeTable(
            seatIds = listOf(player1),
            phase = BlackjackTable.Phase.PLAYER_TURNS,
            actorIndex = 0,
        )
        table.seats[0].status = BlackjackTable.SeatStatus.ACTIVE
        table.seats[0].hand.add(Card(Rank.TWO, Suit.CLUBS)) // 3 cards now

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertFalse(view.canDouble)
    }

    @Test
    fun `snapshot canSplit is true for a pair with fewer than MAX_SPLIT_HANDS`() {
        val table = registry.create(
            guildId = guildId, mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = player1, ante = 100L, maxSeats = 6,
        )
        // Two Aces = a splittable pair
        table.seats.add(BlackjackTable.Seat(
            discordId = player1,
            hand = mutableListOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.ACE, Suit.HEARTS)),
            ante = 100L, stake = 100L,
        ))
        table.dealer.add(Card(Rank.NINE, Suit.SPADES))
        table.dealer.add(Card(Rank.FIVE, Suit.HEARTS))
        table.phase = BlackjackTable.Phase.PLAYER_TURNS
        table.actorIndex = 0
        table.seats[0].status = BlackjackTable.SeatStatus.ACTIVE

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertTrue(view.isMyTurn)
        assertTrue(view.canSplit)
    }

    @Test
    fun `snapshot isMyTurn is false when phase is LOBBY even if viewer is in seat 0`() {
        val table = makeTable(
            seatIds = listOf(player1),
            phase = BlackjackTable.Phase.LOBBY,
            actorIndex = 0,
        )
        table.seats[0].status = BlackjackTable.SeatStatus.ACTIVE
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertFalse(view.isMyTurn)
    }

    // ─── lastResult projection ────────────────────────────────────────

    @Test
    fun `snapshot exposes lastResult when a hand has been resolved`() {
        val table = makeTable(seatIds = listOf(player1))
        val resolvedAt = Instant.ofEpochSecond(1_700_000_000L)
        table.lastResult = BlackjackTable.HandResult(
            handNumber = 3L,
            dealer = listOf(Card(Rank.KING, Suit.CLUBS), Card(Rank.SEVEN, Suit.DIAMONDS)),
            dealerTotal = 17,
            seatResults = mapOf(player1 to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(player1 to 200L),
            pot = 100L,
            rake = 5L,
            resolvedAt = resolvedAt,
        )

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertNotNull(view.lastResult)
        val lr = view.lastResult!!
        assertEquals(3L, lr.handNumber)
        assertEquals(17, lr.dealerTotal)
        assertEquals("PLAYER_WIN", lr.seatResults[player1.toString()])
        assertEquals(200L, lr.payouts[player1.toString()])
        assertEquals(100L, lr.pot)
        assertEquals(5L, lr.rake)
        // Dealer cards rendered as strings
        assertEquals(2, lr.dealer.size)
    }

    @Test
    fun `snapshot lastResult is null when no hand has been played`() {
        val table = makeTable(seatIds = listOf(player1))
        // lastResult defaults to null
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertNull(view.lastResult)
    }

    // ─── lastResult perHandResults projection ─────────────────────────

    @Test
    fun `snapshot lastResult perHandResults maps split-hand details`() {
        val table = makeTable(seatIds = listOf(player1))
        val resolvedAt = Instant.ofEpochSecond(1_700_000_000L)
        table.lastResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.TEN, Suit.CLUBS)),
            dealerTotal = 10,
            seatResults = mapOf(player1 to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(player1 to 100L),
            pot = 50L,
            rake = 2L,
            resolvedAt = resolvedAt,
            perHandResults = listOf(
                BlackjackTable.PerHandResult(
                    discordId = player1,
                    handIndex = 0,
                    cards = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.HEARTS)),
                    total = 21,
                    stake = 100L,
                    doubled = false,
                    fromSplit = true,
                    result = Blackjack.Result.PLAYER_BLACKJACK,
                    payout = 200L,
                )
            ),
        )

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        val perHand = view.lastResult!!.perHandResults
        assertEquals(1, perHand.size)
        assertEquals(player1.toString(), perHand[0].discordId)
        assertEquals(0, perHand[0].handIndex)
        assertEquals(21, perHand[0].total)
        assertEquals(100L, perHand[0].stake)
        assertFalse(perHand[0].doubled)
        assertTrue(perHand[0].fromSplit)
        assertEquals("PLAYER_BLACKJACK", perHand[0].result)
        assertEquals(200L, perHand[0].payout)
    }

    // ─── hand-slot (split) view ───────────────────────────────────────

    @Test
    fun `snapshot seat hands list includes one slot for a non-split seat`() {
        val table = makeTable(seatIds = listOf(player1))

        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        val seat = view.seats[0]
        assertEquals(1, seat.hands.size)
        val slot = seat.hands[0]
        assertEquals(2, slot.cards.size)
        assertFalse(slot.fromSplit)
        assertFalse(slot.doubled)
        assertEquals("ACTIVE", slot.status)
    }

    // ─── listMultiTables ──────────────────────────────────────────────

    @Test
    fun `listMultiTables returns empty list when no multi tables for guild`() {
        assertTrue(service.listMultiTables(guildId = 999L).isEmpty())
    }

    @Test
    fun `listMultiTables filters out SOLO tables`() {
        // Create a SOLO table — should not appear in the lobby list
        registry.create(
            guildId = guildId,
            mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = player1,
            ante = 50L, maxSeats = 1,
        )
        assertTrue(service.listMultiTables(guildId).isEmpty())
    }

    @Test
    fun `listMultiTables resolves host name via member lookup`() {
        val table = makeTable(seatIds = listOf(player1))
        every { memberLookup.resolveAll(guildId, setOf(player1)) } returns mapOf(
            player1 to MemberLookupHelper.MemberDisplay(name = "Alice", avatarUrl = "alice.png"),
        )

        val rows = service.listMultiTables(guildId)

        assertEquals(1, rows.size)
        assertEquals("Alice", rows[0].hostName)
        assertEquals(player1.toString(), rows[0].hostDiscordId)
        assertEquals("MULTI", rows[0].mode)
        assertEquals(100L, rows[0].ante)
    }

    @Test
    fun `listMultiTables falls back to fallbackName when host is not in guild`() {
        makeTable(seatIds = listOf(11119999L))
        every { memberLookup.resolveAll(guildId, any()) } returns emptyMap()
        every { memberLookup.fallbackName(11119999L) } returns "Player 9999"

        val rows = service.listMultiTables(guildId)

        assertEquals(1, rows.size)
        assertEquals("Player 9999", rows[0].hostName)
    }

    @Test
    fun `listMultiTables hostName is dash when host is null`() {
        // A MULTI table with null hostDiscordId
        val table = registry.create(
            guildId = guildId, mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = null, ante = 100L, maxSeats = 6,
        )
        table.seats.add(BlackjackTable.Seat(discordId = player1, ante = 100L, stake = 100L))

        val rows = service.listMultiTables(guildId)
        assertEquals(1, rows.size)
        assertEquals("—", rows[0].hostName)
        assertNull(rows[0].hostDiscordId)
    }

    @Test
    fun `listMultiTables results are sorted by tableId ascending`() {
        val t1 = makeTable(seatIds = listOf(player1))
        val t2 = makeTable(seatIds = listOf(player2))
        // t1 was created before t2 so its id is smaller

        val rows = service.listMultiTables(guildId)
        assertEquals(2, rows.size)
        assertTrue(rows[0].tableId < rows[1].tableId)
    }

    // ─── snapshot structural fields ───────────────────────────────────

    @Test
    fun `snapshot exposes correct table metadata fields`() {
        val table = makeTable(seatIds = listOf(player1))
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertEquals(table.id, view.tableId)
        assertEquals(guildId, view.guildId)
        assertEquals("MULTI", view.mode)
        assertEquals("LOBBY", view.phase)
        assertEquals(100L, view.ante)
        assertEquals(6, view.maxSeats)
        assertEquals(0, view.actorIndex)
        assertNull(view.currentActorDeadlineEpochMillis)
    }

    @Test
    fun `snapshot returns correct seat count`() {
        val table = makeTable(seatIds = listOf(player1, player2))
        val view = service.snapshot(table.id, viewerDiscordId = player1)!!
        assertEquals(2, view.seats.size)
    }

    @Test
    fun `snapshot discordId on seats is stringified`() {
        val snowflake = 553658039266443264L
        val table = makeTable(seatIds = listOf(snowflake))
        val view = service.snapshot(table.id, viewerDiscordId = snowflake)!!
        assertEquals(snowflake.toString(), view.seats[0].discordId)
    }
}
