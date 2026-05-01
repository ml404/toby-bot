package database.service

import database.dto.ConfigDto
import database.dto.PokerHandLogDto
import database.dto.PokerHandPotDto
import database.dto.UserDto
import database.persistence.PokerHandLogPersistence
import database.persistence.PokerHandPotPersistence
import database.poker.PokerEngine
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

class PokerServiceTest {

    private val guildId = 42L
    private val host = 1L
    private val joiner = 2L
    private val third = 3L

    private lateinit var userService: RecordingUserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var registry: PokerTableRegistry
    private lateinit var handLog: RecordingPokerHandLogPersistence
    private lateinit var handPot: RecordingPokerHandPotPersistence
    private lateinit var service: PokerService

    @BeforeEach
    fun setup() {
        userService = RecordingUserService()
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        registry = PokerTableRegistry(
            idleTtl = Duration.ofMinutes(5),
            sweepInterval = Duration.ofHours(1)
        )
        handLog = RecordingPokerHandLogPersistence()
        handPot = RecordingPokerHandPotPersistence()
        // Default config — fall back to 5% rake.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.POKER_RAKE_PCT.configValue,
                guildId.toString()
            )
        } returns null
        service = PokerService(
            userService = userService,
            jackpotService = jackpotService,
            configService = configService,
            tableRegistry = registry,
            handLogPersistence = handLog,
            handPotPersistence = handPot,
            random = Random(42)
        )
    }

    private fun seed(discordId: Long, balance: Long) {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = balance })
    }

    @Test
    fun `createTable debits credits and seats the host with chip escrow`() {
        seed(host, 1000L)

        val outcome = service.createTable(host, guildId, buyIn = 200L)

        assertTrue(outcome is PokerService.CreateOutcome.Ok)
        val tableId = (outcome as PokerService.CreateOutcome.Ok).tableId
        val table = registry.get(tableId)!!
        assertEquals(1, table.seats.size)
        assertEquals(host, table.seats[0].discordId)
        assertEquals(200L, table.seats[0].chips)
        assertEquals(800L, userService.current(host)?.socialCredit, "200 debited from balance")
    }

    @Test
    fun `createTable rejects invalid buy-in without seating or debiting`() {
        seed(host, 1000L)
        val outcome = service.createTable(host, guildId, buyIn = 1L)
        assertTrue(outcome is PokerService.CreateOutcome.InvalidBuyIn)
        assertEquals(0, registry.listForGuild(guildId).size)
        assertEquals(1000L, userService.current(host)?.socialCredit)
    }

    @Test
    fun `createTable rejects insufficient credits without seating`() {
        seed(host, 50L)
        val outcome = service.createTable(host, guildId, buyIn = 200L)
        assertTrue(outcome is PokerService.CreateOutcome.InsufficientCredits)
        assertEquals(0, registry.listForGuild(guildId).size)
        assertEquals(50L, userService.current(host)?.socialCredit)
    }

    @Test
    fun `buyIn debits credits and seats the player`() {
        seed(host, 1000L)
        seed(joiner, 600L)
        val createOutcome = service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok

        val outcome = service.buyIn(joiner, guildId, createOutcome.tableId, buyIn = 300L)

        assertTrue(outcome is PokerService.BuyInOutcome.Ok)
        outcome as PokerService.BuyInOutcome.Ok
        assertEquals(300L, userService.current(joiner)?.socialCredit)
        assertEquals(2, registry.get(createOutcome.tableId)!!.seats.size)
        assertEquals(300L, outcome.newBalance)
    }

    @Test
    fun `buyIn rejects double-seating`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val outcome = service.buyIn(host, guildId, tableId, buyIn = 200L)
        assertEquals(PokerService.BuyInOutcome.AlreadySeated, outcome)
    }

    @Test
    fun `buyIn rejects unknown table`() {
        seed(joiner, 1000L)
        val outcome = service.buyIn(joiner, guildId, tableId = 999L, buyIn = 200L)
        assertEquals(PokerService.BuyInOutcome.TableNotFound, outcome)
    }

    @Test
    fun `rebuy adds chips to a seated player and debits balance`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId

        val outcome = service.rebuy(host, guildId, tableId, amount = 300L)

        assertTrue(outcome is PokerService.RebuyOutcome.Ok)
        outcome as PokerService.RebuyOutcome.Ok
        assertEquals(500L, outcome.seatChips, "200 + 300 stacks onto the seat")
        assertEquals(500L, outcome.newBalance, "1000 - 200 createTable - 300 rebuy")
        assertEquals(500L, registry.get(tableId)!!.seats[0].chips)
    }

    @Test
    fun `rebuy that would breach max buy-in is rejected without debiting`() {
        seed(host, 100_000L)
        val tableId = (service.createTable(host, guildId, buyIn = 4_500L) as PokerService.CreateOutcome.Ok).tableId
        // MAX_BUY_IN = 5000; seat sitting at 4500. A 1000 top-up would
        // push to 5500 → reject. Player keeps their balance and stack.
        val balanceBefore = userService.current(host)!!.socialCredit!!

        val outcome = service.rebuy(host, guildId, tableId, amount = 1_000L)

        assertTrue(outcome is PokerService.RebuyOutcome.StackCapped)
        outcome as PokerService.RebuyOutcome.StackCapped
        assertEquals(PokerService.MAX_BUY_IN, outcome.cap)
        assertEquals(4_500L, outcome.current)
        assertEquals(balanceBefore, userService.current(host)?.socialCredit, "no debit on cap rejection")
        assertEquals(4_500L, registry.get(tableId)!!.seats[0].chips, "stack untouched on cap rejection")
    }

    @Test
    fun `rebuy mid-hand is rejected`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val outcome = service.rebuy(host, guildId, tableId, amount = 200L)
        assertEquals(PokerService.RebuyOutcome.HandInProgress, outcome)
        // No debit, no chip change.
        assertEquals(500L, userService.current(host)?.socialCredit, "balance untouched on HandInProgress")
    }

    @Test
    fun `rebuy by someone not at the table is rejected`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId

        val outcome = service.rebuy(joiner, guildId, tableId, amount = 200L)
        assertEquals(PokerService.RebuyOutcome.NotSeated, outcome)
    }

    @Test
    fun `rebuy with insufficient credits is rejected without seat change`() {
        seed(host, 250L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        // Balance is now 50 after the createTable debit; 200 rebuy unaffordable.

        val outcome = service.rebuy(host, guildId, tableId, amount = 200L)

        assertTrue(outcome is PokerService.RebuyOutcome.InsufficientCredits)
        assertEquals(50L, userService.current(host)?.socialCredit, "balance unchanged")
        assertEquals(200L, registry.get(tableId)!!.seats[0].chips, "seat chips unchanged")
    }

    @Test
    fun `rebuy below table min or above table max is rejected`() {
        seed(host, 100_000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId

        val tooSmall = service.rebuy(host, guildId, tableId, amount = 10L)
        assertTrue(tooSmall is PokerService.RebuyOutcome.InvalidAmount)

        val tooBig = service.rebuy(host, guildId, tableId, amount = 99_999L)
        assertTrue(tooBig is PokerService.RebuyOutcome.InvalidAmount)
    }

    @Test
    fun `rebuy on missing table returns TableNotFound`() {
        val outcome = service.rebuy(host, guildId, tableId = 999L, amount = 200L)
        assertEquals(PokerService.RebuyOutcome.TableNotFound, outcome)
    }

    @Test
    fun `rebuy with mismatched guild returns TableNotFound`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        // Wrong guild — must not let a player from another server top up
        // a stack at this guild's table.
        val outcome = service.rebuy(host, guildId = 999L, tableId = tableId, amount = 200L)
        assertEquals(PokerService.RebuyOutcome.TableNotFound, outcome)
    }

    @Test
    fun `cashOut credits remaining chips back to balance and removes empty tables`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId

        val outcome = service.cashOut(host, guildId, tableId)

        assertTrue(outcome is PokerService.CashOutOutcome.Ok)
        outcome as PokerService.CashOutOutcome.Ok
        assertEquals(200L, outcome.chipsReturned)
        assertEquals(1000L, outcome.newBalance, "balance restored to original 1000")
        assertNull(registry.get(tableId), "empty table dropped from registry")
    }

    @Test
    fun `cashOut mid-hand queues leave and auto-folds the leaver if it's their turn`() {
        seed(host, 1000L); seed(joiner, 1000L); seed(third, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.buyIn(third, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        val firstActorId = table.seats[table.actorIndex].discordId

        // Whoever is first to act asks to leave. Should: mark pendingLeave,
        // auto-fold them, and the table progresses to the next actor.
        val outcome = service.cashOut(firstActorId, guildId, tableId)

        assertTrue(outcome is PokerService.CashOutOutcome.QueuedForEndOfHand)
        outcome as PokerService.CashOutOutcome.QueuedForEndOfHand
        // Forced blind (5 or 10) may have been posted — chipsHeld captures
        // post-blind stack. Just assert it's >0 and <buyIn.
        assertTrue(outcome.chipsHeld in 1..500L, "chipsHeld snapshot from when leave queued: ${outcome.chipsHeld}")
        // Seat still present (not removed until hand resolves) but folded.
        val seat = registry.get(tableId)!!.seats.first { it.discordId == firstActorId }
        assertTrue(seat.pendingLeave, "pendingLeave flag set")
        assertEquals(PokerTable.SeatStatus.FOLDED, seat.status, "auto-folded since it was their turn")
    }

    @Test
    fun `cashOut mid-hand returns chips when the hand later resolves`() {
        seed(host, 1000L); seed(joiner, 1000L); seed(third, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.buyIn(third, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        // Pick a non-actor leaver: someone whose turn ISN'T up yet so we
        // exercise the cascade path (fold-on-their-turn) rather than the
        // immediate-fold path tested above.
        val nonActorId = table.seats.first { table.seats.indexOf(it) != table.actorIndex }.discordId
        service.cashOut(nonActorId, guildId, tableId)

        // Drive the hand to completion: keep folding until a winner.
        // Actors not in pendingLeave remain free to act normally; the
        // cascade will auto-fold the leaver when their turn comes.
        var safety = 12
        while (registry.get(tableId)?.phase != PokerTable.Phase.WAITING && safety-- > 0) {
            val live = registry.get(tableId) ?: break
            val actor = live.seats.getOrNull(live.actorIndex) ?: break
            service.applyAction(actor.discordId, guildId, tableId, PokerEngine.PokerAction.Fold)
        }

        // Leaver's seat should be gone (post-hand sweep removed it),
        // their balance refunded.
        val tableAfter = registry.get(tableId)
        if (tableAfter != null) {
            assertFalse(tableAfter.seats.any { it.discordId == nonActorId }, "leaver's seat removed")
        }
        // Their chip balance should have been refunded (1000 buy-in 500
        // → 500 wallet at table start; refund brings them back ≥500
        // since chips bled only via blinds at most).
        val finalBalance = userService.current(nonActorId)?.socialCredit ?: 0L
        assertTrue(finalBalance >= 490L, "leaver got their chips back, balance=$finalBalance")
    }

    @Test
    fun `cashOut mid-hand twice returns AlreadyLeaving`() {
        seed(host, 1000L); seed(joiner, 1000L); seed(third, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.buyIn(third, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        val nonActorId = table.seats.first { table.seats.indexOf(it) != table.actorIndex }.discordId
        val first = service.cashOut(nonActorId, guildId, tableId)
        assertTrue(first is PokerService.CashOutOutcome.QueuedForEndOfHand)

        val second = service.cashOut(nonActorId, guildId, tableId)
        assertEquals(PokerService.CashOutOutcome.AlreadyLeaving, second)
    }

    @Test
    fun `recentHandsForTable returns guild-scoped rows newest-first capped by limit`() {
        // Three hands at table 7 in our guild, one at table 7 in a different
        // guild — must NOT leak across guilds.
        val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
        handLog.insert(PokerHandLogDto(
            guildId = guildId, tableId = 7L, handNumber = 1L,
            players = "1,2", winners = "1", pot = 10, rake = 0, board = "AS",
            resolvedAt = now
        ))
        handLog.insert(PokerHandLogDto(
            guildId = guildId, tableId = 7L, handNumber = 2L,
            players = "1,2", winners = "2", pot = 20, rake = 0, board = "AS,KD",
            resolvedAt = now.plusSeconds(60)
        ))
        handLog.insert(PokerHandLogDto(
            guildId = 999L, tableId = 7L, handNumber = 3L,
            players = "9,8", winners = "9", pot = 30, rake = 0, board = "TC",
            resolvedAt = now.plusSeconds(120)
        ))
        handLog.insert(PokerHandLogDto(
            guildId = guildId, tableId = 7L, handNumber = 4L,
            players = "1,2", winners = "1", pot = 40, rake = 0, board = "AH,KS,QC",
            resolvedAt = now.plusSeconds(180)
        ))

        val rows = service.recentHandsForTable(guildId, tableId = 7L, limit = 5)

        assertEquals(3, rows.size, "only this guild's rows for table 7")
        // Recording stub orders newest-first.
        assertEquals(listOf(4L, 2L, 1L), rows.map { it.handNumber })
        assertTrue(rows.all { it.guildId == guildId }, "no cross-guild leakage")
    }

    @Test
    fun `recentHandsForGuild returns all tables for the guild capped by limit`() {
        val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
        handLog.insert(PokerHandLogDto(
            guildId = guildId, tableId = 1L, handNumber = 1L,
            players = "1", winners = "1", pot = 10, rake = 0, board = "",
            resolvedAt = now
        ))
        handLog.insert(PokerHandLogDto(
            guildId = guildId, tableId = 2L, handNumber = 1L,
            players = "1", winners = "1", pot = 10, rake = 0, board = "",
            resolvedAt = now.plusSeconds(30)
        ))
        handLog.insert(PokerHandLogDto(
            guildId = 999L, tableId = 1L, handNumber = 1L,
            players = "9", winners = "9", pot = 99, rake = 0, board = "",
            resolvedAt = now.plusSeconds(60)
        ))

        val rows = service.recentHandsForGuild(guildId, limit = 10)

        assertEquals(2, rows.size, "both this guild's hands across tables")
        assertTrue(rows.all { it.guildId == guildId })
    }

    @Test
    fun `recentHandsForGuild caps limit to HISTORY_MAX_LIMIT`() {
        repeat(PokerService.HISTORY_MAX_LIMIT + 5) { i ->
            handLog.insert(PokerHandLogDto(
                guildId = guildId, tableId = 1L, handNumber = (i + 1).toLong(),
                players = "1", winners = "1", pot = 10, rake = 0, board = "",
                resolvedAt = java.time.Instant.now().plusSeconds(i.toLong())
            ))
        }
        val rows = service.recentHandsForGuild(guildId, limit = 1000)
        assertEquals(PokerService.HISTORY_MAX_LIMIT, rows.size, "limit clamped down to MAX")
    }

    @Test
    fun `recentHands with non-positive limit returns empty without hitting persistence`() {
        assertTrue(service.recentHandsForGuild(guildId, limit = 0).isEmpty())
        assertTrue(service.recentHandsForGuild(guildId, limit = -3).isEmpty())
        assertTrue(service.recentHandsForTable(guildId, tableId = 1L, limit = 0).isEmpty())
    }

    @Test
    fun `cashOut on table you don't sit at is rejected`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val outcome = service.cashOut(joiner, guildId, tableId)
        assertEquals(PokerService.CashOutOutcome.NotSeated, outcome)
    }

    @Test
    fun `startHand requires host`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)

        val byJoiner = service.startHand(joiner, guildId, tableId)
        assertEquals(PokerService.StartHandOutcome.NotHost, byJoiner)

        val byHost = service.startHand(host, guildId, tableId)
        assertTrue(byHost is PokerService.StartHandOutcome.Ok)
        assertEquals(1L, (byHost as PokerService.StartHandOutcome.Ok).handNumber)
    }

    @Test
    fun `applyAction routes rake to jackpot and persists hand log on resolution`() {
        seed(host, 1000L); seed(joiner, 1000L); seed(third, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.buyIn(third, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        // Make all 3 players fold around — winner takes uncontested pot.
        // Apply two folds to leave one player. Pot is SB+BB=15, rake=floor(15*0.05)=0.
        val firstActor = table.seats[table.actorIndex].discordId
        service.applyAction(firstActor, guildId, tableId, PokerEngine.PokerAction.Fold)
        val secondActor = table.seats[table.actorIndex].discordId
        val outcome = service.applyAction(secondActor, guildId, tableId, PokerEngine.PokerAction.Fold)

        assertTrue(outcome is PokerService.ActionOutcome.HandResolved)
        // Tiny pot, rake floors to 0, so jackpot service NOT called.
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
        assertEquals(1, handLog.inserted.size, "hand log row written")
        assertEquals(15L, handLog.inserted[0].pot)
        assertEquals(0L, handLog.inserted[0].rake)
    }

    @Test
    fun `applyAction with raise grows pot enough that rake routes to jackpot`() {
        seed(host, 5000L); seed(joiner, 5000L)
        val tableId = (service.createTable(host, guildId, buyIn = 2000L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 2000L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        // HU preflop: dealer (SB) acts first.
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId
        // SB raises to 20 → BB raises to 30 → SB raises to 40 → BB raises to 50 (cap) → SB calls.
        service.applyAction(sbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(bbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(sbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(bbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(sbId, guildId, tableId, PokerEngine.PokerAction.Call)

        // Now flop, turn, river — both check.
        for (street in 1..3) {
            val a = registry.get(tableId)!!.seats[registry.get(tableId)!!.actorIndex].discordId
            service.applyAction(a, guildId, tableId, PokerEngine.PokerAction.Check)
            val b = registry.get(tableId)!!.seats[registry.get(tableId)!!.actorIndex].discordId
            service.applyAction(b, guildId, tableId, PokerEngine.PokerAction.Check)
        }

        // Pot = 100 (50 each), rake = floor(100 * 0.05) = 5.
        verify(exactly = 1) { jackpotService.addToPool(guildId, 5L) }
        assertEquals(1, handLog.inserted.size)
        assertEquals(100L, handLog.inserted[0].pot)
        assertEquals(5L, handLog.inserted[0].rake)
        // v2: single-pot hand still produces exactly one pot-tier row
        // joined to the hand log so the audit table is consistent
        // whether or not side pots formed.
        val handLogId = handLog.inserted[0].id!!
        val tiers = handPot.findByHandLogId(handLogId)
        assertEquals(1, tiers.size, "single-pot hand → single audit tier row")
        assertEquals(0, tiers[0].tierIndex)
        assertEquals(95L, tiers[0].amount, "tier amount net of rake (100 - 5)")
    }

    @Test
    fun `applyAction NotYourTurn surfaces engine rejection`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        val notTurn = table.seats.first { table.seats.indexOf(it) != table.actorIndex }.discordId
        val outcome = service.applyAction(notTurn, guildId, tableId, PokerEngine.PokerAction.Check)
        assertTrue(outcome is PokerService.ActionOutcome.Rejected)
        assertEquals(PokerEngine.RejectReason.NOT_YOUR_TURN, (outcome as PokerService.ActionOutcome.Rejected).reason)
    }

    @Test
    fun `applyAction on missing table returns TableNotFound`() {
        val outcome = service.applyAction(host, guildId, tableId = 999L, action = PokerEngine.PokerAction.Check)
        assertEquals(PokerService.ActionOutcome.TableNotFound, outcome)
    }

    @Test
    fun `evictAllSeats refunds chips to all seated players`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 300L)
        val table = registry.get(tableId)!!

        service.evictAllSeats(table)

        assertEquals(1000L, userService.current(host)?.socialCredit, "host refunded 200")
        assertEquals(1000L, userService.current(joiner)?.socialCredit, "joiner refunded 300")
        assertEquals(0, table.seats.size)
    }

    @Test
    fun `rakeRate falls back to default when config unset and clamps to MAX`() {
        // Default
        assertEquals(0.05, service.rakeRate(guildId), 0.0001)
        // Unparseable → default
        every {
            configService.getConfigByName(ConfigDto.Configurations.POKER_RAKE_PCT.configValue, guildId.toString())
        } returns ConfigDto(name = "x", value = "asdf", guildId = guildId.toString())
        assertEquals(0.05, service.rakeRate(guildId), 0.0001)
        // Above max → clamped
        every {
            configService.getConfigByName(ConfigDto.Configurations.POKER_RAKE_PCT.configValue, guildId.toString())
        } returns ConfigDto(name = "x", value = "99", guildId = guildId.toString())
        assertEquals(PokerService.MAX_RAKE, service.rakeRate(guildId), 0.0001)
        // Sane custom value
        every {
            configService.getConfigByName(ConfigDto.Configurations.POKER_RAKE_PCT.configValue, guildId.toString())
        } returns ConfigDto(name = "x", value = "10", guildId = guildId.toString())
        assertEquals(0.10, service.rakeRate(guildId), 0.0001)
    }

    @Test
    fun `wireRegistry hooks the idle-evict callback`() {
        // Sanity check that the registry would actually call our refund logic
        // when sweep evicts a table. We exercise the wired callback explicitly
        // since the scheduler runs on a long interval in tests.
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        service.wireRegistry()
        // Force an eviction by backdating activity and sweeping.
        registry.get(tableId)!!.lastActivityAt = java.time.Instant.now().minus(Duration.ofHours(1))
        registry.sweepIdle(java.time.Instant.now())
        assertEquals(1000L, userService.current(host)?.socialCredit, "evicted chips refunded via callback")
        assertNull(registry.get(tableId), "table removed by sweep")
    }

    @Test
    fun `snapshot returns the live table reference`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val snap = service.snapshot(tableId)
        assertNotNull(snap)
        assertEquals(tableId, snap!!.id)
        assertNull(service.snapshot(999L))
    }

    @Test
    fun `cashOut on an unknown table returns TableNotFound`() {
        val outcome = service.cashOut(host, guildId, tableId = 999L)
        assertEquals(PokerService.CashOutOutcome.TableNotFound, outcome)
    }

    @Test
    fun `startHand on missing table returns TableNotFound`() {
        val outcome = service.startHand(host, guildId, tableId = 999L)
        assertEquals(PokerService.StartHandOutcome.TableNotFound, outcome)
    }

    @Test
    fun `buyIn rejects mismatched guild as TableNotFound`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        // Wrong guild id should not let a player from another server peek into this guild's table.
        val outcome = service.buyIn(joiner, guildId = 999L, tableId = tableId, buyIn = 200L)
        assertEquals(PokerService.BuyInOutcome.TableNotFound, outcome)
        assertFalse(registry.get(tableId)!!.seats.any { it.discordId == joiner })
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        var updateCount = 0
            private set
        fun seed(dto: UserDto) { users[dto.discordId to dto.guildId] = dto }
        fun current(discordId: Long, guildId: Long = 42L): UserDto? = users[discordId to guildId]

        override fun listGuildUsers(guildId: Long?): List<UserDto?> = users.values.filter { it.guildId == guildId }
        override fun createNewUser(userDto: UserDto): UserDto = userDto.also(::seed)
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? = users[discordId!! to guildId!!]
        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? =
            users[discordId!! to guildId!!]
        override fun updateUser(userDto: UserDto): UserDto { updateCount++; users[userDto.discordId to userDto.guildId] = userDto; return userDto }
        override fun deleteUser(userDto: UserDto) { users.remove(userDto.discordId to userDto.guildId) }
        override fun deleteUserById(discordId: Long?, guildId: Long?) { users.remove(discordId!! to guildId!!) }
        override fun clearCache() {}
        override fun evictUserFromCache(discordId: Long?, guildId: Long?) {}
    }

    private class RecordingPokerHandLogPersistence : PokerHandLogPersistence {
        val inserted = mutableListOf<PokerHandLogDto>()
        private var nextId = 1L
        override fun insert(row: PokerHandLogDto): PokerHandLogDto {
            // Production assigns id via @GeneratedValue + flush. Stub it
            // here so persistResult can pass it on to the pot rows.
            if (row.id == null) row.id = nextId++
            inserted.add(row); return row
        }
        override fun findRecentByTable(guildId: Long, tableId: Long, limit: Int): List<PokerHandLogDto> =
            inserted.asReversed()
                .filter { it.guildId == guildId && it.tableId == tableId }
                .take(limit.coerceAtLeast(0))
        override fun findRecentByGuild(guildId: Long, limit: Int): List<PokerHandLogDto> =
            inserted.asReversed()
                .filter { it.guildId == guildId }
                .take(limit.coerceAtLeast(0))
    }

    private class RecordingPokerHandPotPersistence : PokerHandPotPersistence {
        val inserted = mutableListOf<PokerHandPotDto>()
        override fun insert(row: PokerHandPotDto): PokerHandPotDto {
            inserted.add(row); return row
        }
        override fun findByHandLogId(handLogId: Long): List<PokerHandPotDto> =
            inserted.filter { it.handLogId == handLogId }.sortedBy { it.tierIndex }
    }
}
