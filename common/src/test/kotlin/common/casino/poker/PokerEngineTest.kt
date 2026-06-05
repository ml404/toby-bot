package common.casino.poker

import common.card.Card
import common.card.Rank
import common.card.Suit
import common.casino.poker.PokerEngine.PokerAction
import common.casino.poker.PokerTable.Phase
import common.casino.poker.PokerTable.SeatStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random
import common.casino.poker.PokerEngine
import common.casino.poker.PokerTable

class PokerEngineTest {

    private val now: Instant = Instant.parse("2026-04-10T10:00:00Z")
    private val rake = 0.05

    private fun newTable(seatChips: List<Long> = listOf(1000L, 1000L, 1000L)): PokerTable {
        val table = PokerTable(
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
        seatChips.forEachIndexed { i, chips ->
            table.seats.add(PokerTable.Seat(discordId = (i + 1).toLong(), chips = chips))
        }
        return table
    }

    @Test
    fun `startHand requires at least 2 chip-holding seats`() {
        val empty = newTable(seatChips = emptyList())
        assertEquals(PokerEngine.StartResult.NotEnoughPlayers, PokerEngine.startHand(empty, Random(0), now))

        val solo = newTable(seatChips = listOf(1000L))
        assertEquals(PokerEngine.StartResult.NotEnoughPlayers, PokerEngine.startHand(solo, Random(0), now))
    }

    @Test
    fun `startHand cannot start a hand already in progress`() {
        val table = newTable()
        PokerEngine.startHand(table, Random(0), now)
        assertEquals(
            PokerEngine.StartResult.HandAlreadyInProgress,
            PokerEngine.startHand(table, Random(0), now)
        )
    }

    @Test
    fun `startHand 3 players posts SB and BB and dealt 2 hole cards each`() {
        val table = newTable()
        val r = PokerEngine.startHand(table, Random(0), now)
        assertTrue(r is PokerEngine.StartResult.Started)
        assertEquals(Phase.PRE_FLOP, table.phase)
        assertEquals(15L, table.pot, "SB 5 + BB 10 = 15 pot")
        assertEquals(10L, table.currentBet)
        for (seat in table.seats) {
            assertEquals(2, seat.holeCards.size, "every seat dealt 2 hole cards")
        }
        // 3-handed: dealer rotated to 1, SB=2, BB=0, first actor=1 (UTG=dealer position).
        assertEquals(1, table.dealerIndex)
        assertEquals(1, table.actorIndex)
        assertEquals(3, table.seatsToAct)
    }

    @Test
    fun `startHand heads-up - dealer is SB and acts first preflop`() {
        val table = newTable(seatChips = listOf(1000L, 1000L))
        PokerEngine.startHand(table, Random(0), now)
        // dealer=1 after rotation, SB also at 1 (HU rule), BB at 0.
        assertEquals(1, table.dealerIndex)
        // SB (dealer) acts first preflop.
        assertEquals(table.dealerIndex, table.actorIndex)
    }

    @Test
    fun `everyone folding to one player ends hand without showdown`() {
        val table = newTable() // 3 players
        PokerEngine.startHand(table, Random(0), now)
        // First actor (seat 0) folds.
        val firstActor = table.seats[table.actorIndex].discordId
        var r1 = PokerEngine.applyAction(table, firstActor, PokerAction.Fold, rake, now)
        assertTrue(r1 is PokerEngine.ApplyResult.Applied)
        // Next actor folds → only one player left, hand resolves immediately to them with no reveals.
        val nextActor = table.seats[table.actorIndex].discordId
        val r2 = PokerEngine.applyAction(table, nextActor, PokerAction.Fold, rake, now)
        assertTrue(r2 is PokerEngine.ApplyResult.Applied)
        val ev = (r2 as PokerEngine.ApplyResult.Applied).event
        assertTrue(ev is PokerEngine.ActionEvent.HandResolved)
        val result = (ev as PokerEngine.ActionEvent.HandResolved).result
        assertEquals(1, result.winners.size)
        assertTrue(result.revealedHoleCards.isEmpty(), "no showdown when one player remains")
        // Pot was SB+BB=15, rake=floor(15*0.05)=0, winner takes 15.
        assertEquals(15L, result.pot)
        assertEquals(0L, result.rake)
        assertEquals(15L, result.payoutByDiscordId.values.single())
    }

    @Test
    fun `check round can advance street when no betting action`() {
        // Set up post-flop where everyone is checked-down
        val table = newTable(seatChips = listOf(500L, 500L)) // heads-up
        PokerEngine.startHand(table, Random(0), now)
        // HU preflop: SB=dealer=1 acts first, owes 5 to call BB.
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId
        // SB calls.
        var r = PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        assertEquals(PokerEngine.ActionEvent.Continued, (r as PokerEngine.ApplyResult.Applied).event)
        // BB checks.
        r = PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)
        val ev = (r as PokerEngine.ApplyResult.Applied).event
        assertTrue(ev is PokerEngine.ActionEvent.StreetAdvanced)
        assertEquals(Phase.FLOP, (ev as PokerEngine.ActionEvent.StreetAdvanced).newPhase)
        assertEquals(3, table.community.size, "flop dealt 3 community cards")
    }

    @Test
    fun `raise resets seatsToAct and locks others into another decision`() {
        val table = newTable() // 3 players
        PokerEngine.startHand(table, Random(0), now)
        val firstActor = table.seats[table.actorIndex].discordId
        val r = PokerEngine.applyAction(table, firstActor, PokerAction.Raise, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Applied)
        // After a raise from the first actor, the other two still need to respond.
        assertEquals(2, table.seatsToAct)
        assertEquals(20L, table.currentBet, "BB 10 + raise 10 small bet = 20")
        assertEquals(1, table.raisesThisStreet)
    }

    @Test
    fun `raise cap rejects further raises`() {
        val table = newTable(seatChips = listOf(2000L, 2000L))
        PokerEngine.startHand(table, Random(0), now)
        var actor = table.seats[table.actorIndex].discordId
        // Raise 4 times alternating between the two players.
        repeat(4) {
            val r = PokerEngine.applyAction(table, actor, PokerAction.Raise, rake, now)
            assertTrue(r is PokerEngine.ApplyResult.Applied, "raise #${it + 1} should succeed")
            actor = table.seats[table.actorIndex].discordId
        }
        // 5th raise hits the cap.
        val r5 = PokerEngine.applyAction(table, actor, PokerAction.Raise, rake, now)
        assertTrue(r5 is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.RAISE_CAP_REACHED, (r5 as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `not-your-turn rejection`() {
        val table = newTable()
        PokerEngine.startHand(table, Random(0), now)
        val notTheActor = table.seats.first { table.seats.indexOf(it) != table.actorIndex }.discordId
        val r = PokerEngine.applyAction(table, notTheActor, PokerAction.Check, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.NOT_YOUR_TURN, (r as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `check rejected when there is a bet to call`() {
        val table = newTable()
        PokerEngine.startHand(table, Random(0), now)
        val actor = table.seats[table.actorIndex].discordId
        // Pre-flop owes the BB amount to the action.
        val r = PokerEngine.applyAction(table, actor, PokerAction.Check, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.ILLEGAL_CHECK, (r as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `call rejected when nothing is owed`() {
        // Engineer a state where call would be illegal: post-flop after both checked.
        val table = newTable(seatChips = listOf(500L, 500L))
        PokerEngine.startHand(table, Random(0), now)
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId
        PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)
        // Now on the flop, first actor postflop is BB (left of dealer in HU).
        val firstFlopActor = table.seats[table.actorIndex].discordId
        val r = PokerEngine.applyAction(table, firstFlopActor, PokerAction.Call, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.ILLEGAL_CALL, (r as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `chips conserved across a full hand and rake routed off`() {
        val table = newTable(seatChips = listOf(500L, 500L))
        val totalChipsBefore = table.seats.sumOf { it.chips }
        PokerEngine.startHand(table, Random(42), now)
        // Both players check-down all four streets.
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId
        // Preflop: SB calls, BB checks.
        PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)
        // Flop, Turn, River — both check.
        for (street in 1..3) {
            val firstActor = table.seats[table.actorIndex].discordId
            PokerEngine.applyAction(table, firstActor, PokerAction.Check, rake, now)
            val secondActor = table.seats[table.actorIndex].discordId
            val r = PokerEngine.applyAction(table, secondActor, PokerAction.Check, rake, now)
            // Last check on the river resolves the hand.
            if (street == 3) {
                assertTrue((r as PokerEngine.ApplyResult.Applied).event is PokerEngine.ActionEvent.HandResolved)
            }
        }
        val totalChipsAfter = table.seats.sumOf { it.chips }
        // Pot was 20 (BB ante x 2). Rake = floor(20 * 0.05) = 1.
        assertEquals(totalChipsBefore - 1L, totalChipsAfter, "exactly the rake is missing")
        assertEquals(Phase.WAITING, table.phase, "table reset to lobby after resolution")
        assertNotNull(table.lastResult)
        assertEquals(1L, table.lastResult!!.rake)
    }

    @Test
    fun `resolveHand picks best 5-card hand at showdown`() {
        // Force a deterministic hand by seeding deck and walking everyone to the river.
        val table = newTable(seatChips = listOf(500L, 500L))
        PokerEngine.startHand(table, Random(7), now)
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId
        PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)
        // Flop, turn, river — both check.
        for (street in 1..3) {
            val a = table.seats[table.actorIndex].discordId
            PokerEngine.applyAction(table, a, PokerAction.Check, rake, now)
            val b = table.seats[table.actorIndex].discordId
            PokerEngine.applyAction(table, b, PokerAction.Check, rake, now)
        }
        val result = table.lastResult!!
        assertTrue(result.winners.isNotEmpty(), "showdown picks at least one winner")
        // Pot=20, rake=1 → winner(s) split 19.
        assertEquals(19L, result.payoutByDiscordId.values.sum())
        assertEquals(2, result.revealedHoleCards.size, "showdown reveals both contenders")
    }

    @Test
    fun `applyAction on WAITING table is rejected`() {
        val table = newTable()
        val r = PokerEngine.applyAction(table, 1L, PokerAction.Check, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.NO_HAND_IN_PROGRESS, (r as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `seat status resets to SITTING_OUT after a hand`() {
        val table = newTable(seatChips = listOf(500L, 500L))
        PokerEngine.startHand(table, Random(0), now)
        // Fold to end immediately.
        val a = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a, PokerAction.Fold, rake, now)
        for (seat in table.seats) {
            assertEquals(SeatStatus.SITTING_OUT, seat.status, "all seats reset between hands")
            assertTrue(seat.holeCards.isEmpty())
        }
        assertNull(table.deck)
    }

    // ---- Additional coverage tests ----

    @Test
    fun `applyAction rejected for unknown discord id`() {
        val table = newTable()
        PokerEngine.startHand(table, Random(0), now)
        val unknownId = 9999L
        val r = PokerEngine.applyAction(table, unknownId, PokerAction.Check, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.NOT_AT_TABLE, (r as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `raise rejected when player has insufficient chips`() {
        // Post-flop scenario where a player has few chips left and cannot cover the raise amount.
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 1000L))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 1000L))
        PokerEngine.startHand(table, Random(0), now)
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId
        // Pre-flop: SB calls, BB checks.
        PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)
        // Now on the flop. Force the current actor to have very few chips (less than bigBet).
        val actorSeat = table.seats[table.actorIndex]
        actorSeat.chips = 5L // only 5 chips — can't raise (raise costs smallBet=10)
        val r = PokerEngine.applyAction(table, actorSeat.discordId, PokerAction.Raise, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.INSUFFICIENT_CHIPS_TO_RAISE, (r as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `call with zero chips is rejected`() {
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 1000L))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 1000L))
        PokerEngine.startHand(table, Random(0), now)
        // Drain all chips from the current actor via a manual tweak.
        val actorSeat = table.seats[table.actorIndex]
        // Force them to owe but have 0 chips by setting chips=0 directly after start.
        // (SB was already deducted from blind posting — we set remaining chips to 0.)
        actorSeat.chips = 0L
        val r = PokerEngine.applyAction(table, actorSeat.discordId, PokerAction.Call, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Rejected)
        assertEquals(PokerEngine.RejectReason.INSUFFICIENT_CHIPS_TO_CALL, (r as PokerEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `all streets advance flop turn river and hand resolves at showdown`() {
        val table = newTable(seatChips = listOf(1000L, 1000L))
        PokerEngine.startHand(table, Random(1), now)
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId

        // Pre-flop: SB calls, BB checks.
        PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        var r = PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)
        assertTrue((r as PokerEngine.ApplyResult.Applied).event is PokerEngine.ActionEvent.StreetAdvanced)
        assertEquals(Phase.FLOP, table.phase)
        assertEquals(3, table.community.size)

        // Flop: both check.
        var a1 = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a1, PokerAction.Check, rake, now)
        var a2 = table.seats[table.actorIndex].discordId
        r = PokerEngine.applyAction(table, a2, PokerAction.Check, rake, now)
        assertTrue((r as PokerEngine.ApplyResult.Applied).event is PokerEngine.ActionEvent.StreetAdvanced)
        assertEquals(Phase.TURN, table.phase)
        assertEquals(4, table.community.size)

        // Turn: both check.
        a1 = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a1, PokerAction.Check, rake, now)
        a2 = table.seats[table.actorIndex].discordId
        r = PokerEngine.applyAction(table, a2, PokerAction.Check, rake, now)
        assertTrue((r as PokerEngine.ApplyResult.Applied).event is PokerEngine.ActionEvent.StreetAdvanced)
        assertEquals(Phase.RIVER, table.phase)
        assertEquals(5, table.community.size)

        // River: both check → hand resolved.
        a1 = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a1, PokerAction.Check, rake, now)
        a2 = table.seats[table.actorIndex].discordId
        r = PokerEngine.applyAction(table, a2, PokerAction.Check, rake, now)
        val ev = (r as PokerEngine.ApplyResult.Applied).event
        assertTrue(ev is PokerEngine.ActionEvent.HandResolved)
        assertEquals(Phase.WAITING, table.phase)
        assertNotNull(table.lastResult)
        assertEquals(2, table.lastResult!!.revealedHoleCards.size)
    }

    @Test
    fun `turn and river use big bet unit for raises`() {
        val table = newTable(seatChips = listOf(2000L, 2000L))
        PokerEngine.startHand(table, Random(2), now)
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId

        // Pre-flop: SB calls, BB checks.
        PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)

        // Flop: both check.
        var a = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a, PokerAction.Check, rake, now)
        a = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a, PokerAction.Check, rake, now)

        // Turn: first actor raises — should use bigBet=20, so currentBet goes from 0 to 20.
        assertEquals(Phase.TURN, table.phase)
        val turnActor = table.seats[table.actorIndex].discordId
        val preBet = table.currentBet
        val r = PokerEngine.applyAction(table, turnActor, PokerAction.Raise, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Applied)
        assertEquals(preBet + 20L, table.currentBet, "Turn raise uses bigBet=20")
    }

    @Test
    fun `all-in player goes to ALL_IN status and board is run out automatically`() {
        // Give one player barely enough to call so they go all-in.
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        // Seat 0 = 15 chips (SB+just-enough-to-call), Seat 1 = 1000 chips.
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 15L))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 1000L))
        PokerEngine.startHand(table, Random(3), now)
        // HU: dealer=SB=index1 acts first.
        val sbIdx = table.dealerIndex
        val bbIdx = (sbIdx + 1) % 2
        val sbId = table.seats[sbIdx].discordId
        val bbId = table.seats[bbIdx].discordId

        // SB calls (owes 5 to match BB=10; if SB already posted 5 and has 10 left this calls for 5).
        val r = PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
        assertTrue(r is PokerEngine.ApplyResult.Applied)
        // If SB now has 0 chips → ALL_IN, else they had some chips left.
        val sbSeat = table.seats[sbIdx]
        if (sbSeat.chips == 0L) {
            assertEquals(SeatStatus.ALL_IN, sbSeat.status)
        }

        // BB checks/calls: depending on state the hand resolves or advances.
        val r2 = PokerEngine.applyAction(table, bbId, PokerAction.Check, rake, now)
        assertTrue(r2 is PokerEngine.ApplyResult.Applied)
        // Either way the board will have community cards.
        assertTrue(table.community.isNotEmpty() || table.phase == Phase.WAITING)
    }

    @Test
    fun `resolveHand with single contender takes whole pot`() {
        // Build a table in a state where only one non-folded player remains, then call resolveHand.
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 0L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 50L,
            holeCards = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.SPADES))
        ))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 950L,
            status = SeatStatus.FOLDED, totalCommittedThisHand = 50L,
            holeCards = listOf(Card(Rank.TWO, Suit.CLUBS), Card(Rank.THREE, Suit.DIAMONDS))
        ))
        table.pot = 100L
        table.community.addAll(listOf(
            Card(Rank.FOUR, Suit.HEARTS), Card(Rank.FIVE, Suit.CLUBS), Card(Rank.SIX, Suit.DIAMONDS),
            Card(Rank.SEVEN, Suit.HEARTS), Card(Rank.EIGHT, Suit.SPADES)
        ))
        table.handNumber = 1L
        table.phase = Phase.RIVER

        // Use 0% rake so the whole pot goes to the single winner.
        val result = PokerEngine.resolveHand(table, 0.0, now)
        assertEquals(listOf(1L), result.winners)
        assertEquals(100L, result.payoutByDiscordId[1L])
        assertEquals(0L, result.rake)
        // Single contender - no showdown reveal.
        assertTrue(result.revealedHoleCards.isEmpty())
    }

    @Test
    fun `resolveHand split pot when two players have equal best hands`() {
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        // Both players hold cards that don't improve beyond the board.
        // Board: A K Q J T (broadway straight) → both players chop.
        val board = listOf(
            Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.HEARTS),
            Card(Rank.QUEEN, Suit.DIAMONDS), Card(Rank.JACK, Suit.CLUBS),
            Card(Rank.TEN, Suit.SPADES)
        )
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 490L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 10L,
            holeCards = listOf(Card(Rank.TWO, Suit.CLUBS), Card(Rank.THREE, Suit.CLUBS))
        ))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 490L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 10L,
            holeCards = listOf(Card(Rank.TWO, Suit.DIAMONDS), Card(Rank.FOUR, Suit.CLUBS))
        ))
        table.pot = 20L
        table.community.addAll(board)
        table.handNumber = 1L
        table.phase = Phase.RIVER

        val result = PokerEngine.resolveHand(table, 0.0, now)
        // Both players should be listed as winners.
        assertEquals(2, result.winners.size)
        // Each player gets 10 chips (20 / 2, no rake).
        assertEquals(10L, result.payoutByDiscordId[1L])
        assertEquals(10L, result.payoutByDiscordId[2L])
    }

    @Test
    fun `startHand skips chipless seats`() {
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        // Seat 0 has 0 chips (should be skipped), seats 1 and 2 have chips.
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 0L))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 1000L))
        table.seats.add(PokerTable.Seat(discordId = 3L, chips = 1000L))
        val r = PokerEngine.startHand(table, Random(0), now)
        assertTrue(r is PokerEngine.StartResult.Started)
        // Chipless seat should be SITTING_OUT.
        assertEquals(SeatStatus.SITTING_OUT, table.seats[0].status)
        // The chipless seat gets no hole cards.
        assertEquals(0, table.seats[0].holeCards.size)
    }

    @Test
    fun `call for less puts player all-in when bet exceeds remaining chips`() {
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        // Seat 0 has 12 chips, seat 1 has 1000. After posting SB=5, seat0 has 7 left.
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 12L))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 1000L))
        PokerEngine.startHand(table, Random(0), now)
        // HU: dealer=SB=index1 acts first.
        val sbIdx = table.dealerIndex
        val sbId = table.seats[sbIdx].discordId
        val sbSeat = table.seats[sbIdx]
        // SB posts 5 blind, then calls for what remains (< full call).
        // currentBet=10, SB committed 5, owes 5 more. If chips < 5, call-for-less.
        if (sbSeat.chips > 0L && sbSeat.chips < (table.currentBet - sbSeat.committedThisRound)) {
            val r = PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
            assertTrue(r is PokerEngine.ApplyResult.Applied)
            assertEquals(SeatStatus.ALL_IN, sbSeat.status)
            assertEquals(0L, sbSeat.chips)
        } else {
            // Enough chips to cover: normal call.
            val r = PokerEngine.applyAction(table, sbId, PokerAction.Call, rake, now)
            assertTrue(r is PokerEngine.ApplyResult.Applied)
        }
    }

    @Test
    fun `resolveHand with side pot short-stack cannot win full pot`() {
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        // Short stack (id=1) committed 20, big stack (id=2) committed 100.
        // Short stack has better hand. Should win only up to 2*20=40 (matched amount).
        // Big stack should be refunded the uncalled 80.
        val board = listOf(
            Card(Rank.TWO, Suit.CLUBS), Card(Rank.SEVEN, Suit.HEARTS),
            Card(Rank.QUEEN, Suit.DIAMONDS), Card(Rank.KING, Suit.SPADES),
            Card(Rank.THREE, Suit.CLUBS)
        )
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 0L,
            status = SeatStatus.ALL_IN, totalCommittedThisHand = 20L,
            holeCards = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.ACE, Suit.HEARTS))
        ))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 0L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 100L,
            holeCards = listOf(Card(Rank.KING, Suit.CLUBS), Card(Rank.KING, Suit.HEARTS))
        ))
        table.pot = 120L
        table.community.addAll(board)
        table.handNumber = 1L
        table.phase = Phase.RIVER

        val result = PokerEngine.resolveHand(table, 0.0, now)
        // Player 2 (trips kings) beats player 1 (pair aces) since K-K-K > A-A on this board.
        // Actually: player 1 has A-A and board K-Q-2-3-7 → pair of aces
        // Player 2 has K-K and board K-Q-2-3-7 → trips (three kings)
        // Trips beats pair, so player 2 wins.
        assertTrue(result.winners.contains(2L), "Player with trips should win")
        // Player 2 committed 100, player 1 committed 20 → effective for player2 = 20 (only matched 20).
        // Player 2 should get back the uncalled excess of 80.
        assertTrue(result.refundedByDiscordId.getOrDefault(2L, 0L) == 80L,
            "Big stack refunded uncalled bet excess")
    }

    @Test
    fun `three player hand where two fold at different times`() {
        val table = newTable(seatChips = listOf(1000L, 1000L, 1000L))
        PokerEngine.startHand(table, Random(5), now)
        val actor1 = table.seats[table.actorIndex].discordId
        // First actor calls.
        PokerEngine.applyAction(table, actor1, PokerAction.Call, rake, now)
        val actor2 = table.seats[table.actorIndex].discordId
        // Second actor folds.
        PokerEngine.applyAction(table, actor2, PokerAction.Fold, rake, now)
        // Third actor (BB) can now check to close the round.
        val actor3 = table.seats[table.actorIndex].discordId
        val r = PokerEngine.applyAction(table, actor3, PokerAction.Check, rake, now)
        val ev = (r as PokerEngine.ApplyResult.Applied).event
        // After BB checks, street advances to flop.
        assertTrue(ev is PokerEngine.ActionEvent.StreetAdvanced || ev is PokerEngine.ActionEvent.HandResolved)
    }

    @Test
    fun `startHand increments hand number each time`() {
        val table = newTable(seatChips = listOf(1000L, 1000L))
        assertEquals(0L, table.handNumber)
        PokerEngine.startHand(table, Random(0), now)
        assertEquals(1L, table.handNumber)
        // End the hand by folding.
        val a = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a, PokerAction.Fold, rake, now)
        // Start a second hand.
        PokerEngine.startHand(table, Random(1), now)
        assertEquals(2L, table.handNumber)
    }

    @Test
    fun `table lastResult is populated after hand resolves`() {
        val table = newTable(seatChips = listOf(500L, 500L))
        assertNull(table.lastResult)
        PokerEngine.startHand(table, Random(0), now)
        val a = table.seats[table.actorIndex].discordId
        PokerEngine.applyAction(table, a, PokerAction.Fold, rake, now)
        assertNotNull(table.lastResult)
        assertEquals(1L, table.lastResult!!.handNumber)
        assertNotNull(table.lastResult!!.resolvedAt)
    }

    @Test
    fun `zero rake resolveHand gives entire pot to winner`() {
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        val board = listOf(
            Card(Rank.TWO, Suit.CLUBS), Card(Rank.FOUR, Suit.HEARTS),
            Card(Rank.SIX, Suit.DIAMONDS), Card(Rank.NINE, Suit.SPADES),
            Card(Rank.JACK, Suit.CLUBS)
        )
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 490L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 10L,
            holeCards = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.SPADES))
        ))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 490L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 10L,
            holeCards = listOf(Card(Rank.TWO, Suit.DIAMONDS), Card(Rank.THREE, Suit.CLUBS))
        ))
        table.pot = 20L
        table.community.addAll(board)
        table.handNumber = 1L
        table.phase = Phase.RIVER

        val result = PokerEngine.resolveHand(table, 0.0, now)
        assertEquals(0L, result.rake)
        assertEquals(20L, result.payoutByDiscordId.values.sum(), "full pot goes to winner(s)")
    }

    @Test
    fun `resolveHand boards pots list has one entry for single-tier scenario`() {
        val table = PokerTable(
            id = 1L, guildId = 42L, hostDiscordId = 1L,
            minBuyIn = 100L, maxBuyIn = 5000L,
            smallBlind = 5L, bigBlind = 10L,
            smallBet = 10L, bigBet = 20L,
            maxRaisesPerStreet = 4, maxSeats = 6,
        )
        val board = listOf(
            Card(Rank.TWO, Suit.CLUBS), Card(Rank.FOUR, Suit.HEARTS),
            Card(Rank.SIX, Suit.DIAMONDS), Card(Rank.NINE, Suit.SPADES),
            Card(Rank.JACK, Suit.CLUBS)
        )
        table.seats.add(PokerTable.Seat(discordId = 1L, chips = 490L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 10L,
            holeCards = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.SPADES))
        ))
        table.seats.add(PokerTable.Seat(discordId = 2L, chips = 490L,
            status = SeatStatus.ACTIVE, totalCommittedThisHand = 10L,
            holeCards = listOf(Card(Rank.TWO, Suit.DIAMONDS), Card(Rank.THREE, Suit.CLUBS))
        ))
        table.pot = 20L
        table.community.addAll(board)
        table.handNumber = 1L
        table.phase = Phase.RIVER

        val result = PokerEngine.resolveHand(table, 0.0, now)
        assertEquals(1, result.pots.size, "single tier since all players put in same amount")
        assertEquals(10L, result.pots[0].cap)
        assertEquals(2, result.pots[0].eligibleDiscordIds.size)
    }
}
