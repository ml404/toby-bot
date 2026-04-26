package database.poker

import database.poker.PokerEngine.PokerAction
import database.poker.PokerTable.Phase
import database.poker.PokerTable.SeatStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

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
}
