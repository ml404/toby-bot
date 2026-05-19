package database.service

import database.dto.ConfigDto
import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto
import database.dto.UserDto
import database.persistence.JackpotLotteryPersistence
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class JackpotLotteryServiceTest {

    private val guildId = 100L

    private lateinit var lotteryPersistence: JackpotLotteryPersistence
    private lateinit var jackpotService: JackpotService
    private lateinit var userService: UserService
    private lateinit var configService: ConfigService
    private lateinit var service: JackpotLotteryService

    @BeforeEach
    fun setup() {
        lotteryPersistence = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        // Default: no LOTTERY_DAILY_REVENUE_JACKPOT_PCT row → 30% to jackpot.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT.configValue,
                guildId.toString()
            )
        } returns null
        // Default: disable the participation gate (min buyers = 1) so the
        // bulk of the existing tests stay focused on payout maths. The
        // BelowMinBuyers test cases below opt in by stubbing a higher
        // threshold per-test.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_DAILY_MIN_BUYERS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_DAILY_MIN_BUYERS.configValue,
            value = "1",
            guildId = guildId.toString()
        )
        service = JackpotLotteryService(
            lotteryPersistence,
            jackpotService,
            userService,
            configService,
            random = Random(42),
        )
    }

    // ===================================================================
    // TICKET_WEIGHTED tests
    // ===================================================================

    @Test
    fun `openLottery rejects invalid params`() {
        val r = service.openLottery(guildId, ticketPrice = 0L, durationHours = 1, winnerCount = 1, drainPct = 1.0)
        assertTrue(r is JackpotLotteryService.OpenOutcome.InvalidParams)

        val r2 = service.openLottery(guildId, ticketPrice = 10L, durationHours = 0, winnerCount = 1, drainPct = 1.0)
        assertTrue(r2 is JackpotLotteryService.OpenOutcome.InvalidParams)

        val r3 = service.openLottery(guildId, ticketPrice = 10L, durationHours = 1, winnerCount = 0, drainPct = 1.0)
        assertTrue(r3 is JackpotLotteryService.OpenOutcome.InvalidParams)

        val r4 = service.openLottery(guildId, ticketPrice = 10L, durationHours = 1, winnerCount = 1, drainPct = 1.5)
        assertTrue(r4 is JackpotLotteryService.OpenOutcome.InvalidParams)
    }

    @Test
    fun `openLottery rejects when one is already open`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns JackpotLotteryDto(id = 1L, guildId = guildId, status = JackpotLotteryDto.STATUS_OPEN)

        val r = service.openLottery(guildId, ticketPrice = 100L, durationHours = 24, winnerCount = 3, drainPct = 1.0)
        assertEquals(JackpotLotteryService.OpenOutcome.AlreadyOpen, r)
    }

    @Test
    fun `openLottery rejects on empty pool`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns null
        every { jackpotService.getPool(guildId) } returns 0L

        val r = service.openLottery(guildId, ticketPrice = 100L, durationHours = 24, winnerCount = 3, drainPct = 1.0)
        assertEquals(JackpotLotteryService.OpenOutcome.EmptyPool, r)
    }

    @Test
    fun `openLottery seeds the lottery from a fraction of the jackpot pool`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns null
        every { jackpotService.getPool(guildId) } returns 110_000L
        every { jackpotService.resetPool(guildId) } returns 110_000L
        val saved = slot<JackpotLotteryDto>()
        every { lotteryPersistence.upsert(capture(saved)) } answers { saved.captured.also { it.id = 99L } }

        val r = service.openLottery(guildId, ticketPrice = 100L, durationHours = 48, winnerCount = 3, drainPct = 0.5)
        assertTrue(r is JackpotLotteryService.OpenOutcome.Ok)
        r as JackpotLotteryService.OpenOutcome.Ok
        assertEquals(55_000L, r.seeded)
        assertEquals(JackpotLotteryDto.STATUS_OPEN, saved.captured.status)
        assertEquals(JackpotLotteryDto.MODE_TICKET_WEIGHTED, saved.captured.mode)
        assertEquals(100L, saved.captured.ticketPrice)
        assertEquals(3, saved.captured.winnerCount)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 55_000L) }
    }

    @Test
    fun `buyTickets rejects when no lottery is open`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns null

        assertEquals(
            JackpotLotteryService.BuyOutcome.NoOpenLottery,
            service.buyTickets(guildId, discordId = 7L, ticketCount = 5)
        )
    }

    @Test
    fun `buyTickets rejects when user can't afford`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, status = JackpotLotteryDto.STATUS_OPEN
        )
        val user = UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 200L }
        every { userService.getUserByIdForUpdate(7L, guildId) } returns user

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 5)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Insufficient)
        r as JackpotLotteryService.BuyOutcome.Insufficient
        assertEquals(200L, r.have)
        assertEquals(500L, r.need)
    }

    @Test
    fun `buyTickets debits user, increments tickets, grows pool`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1000L,
            status = JackpotLotteryDto.STATUS_OPEN, mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        val user = UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 1_000L }
        every { userService.getUserByIdForUpdate(7L, guildId) } returns user
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns null

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 5)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
        r as JackpotLotteryService.BuyOutcome.Ok
        assertEquals(5, r.ticketCount)
        assertEquals(500L, r.totalSpent)
        assertEquals(500L, r.newBalance)
        assertEquals(1500L, r.newPool)
        assertEquals(500L, user.socialCredit)
        assertEquals(1500L, lottery.poolAmount)
    }

    @Test
    fun `drawLottery rejects when no lottery open`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns null
        assertEquals(JackpotLotteryService.DrawOutcome.NoOpenLottery, service.drawLottery(guildId))
    }

    @Test
    fun `drawLottery rejects when no tickets sold`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, status = JackpotLotteryDto.STATUS_OPEN
        )
        every { lotteryPersistence.ticketsByLottery(1L) } returns emptyList()
        assertEquals(JackpotLotteryService.DrawOutcome.NoTickets, service.drawLottery(guildId))
    }

    @Test
    fun `drawLottery splits the pool 50-30-20 across three winners`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 1, spent = 100L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 2L, ticketCount = 1, spent = 100L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 3L, ticketCount = 1, spent = 100L),
        )
        val users = (1L..3L).associate { id ->
            id to UserDto(discordId = id, guildId = guildId).apply { socialCredit = 0L }
        }
        users.forEach { (id, u) ->
            every { userService.getUserByIdForUpdate(id, guildId) } returns u
        }

        val r = service.drawLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawOutcome.Ok)
        r as JackpotLotteryService.DrawOutcome.Ok
        assertEquals(1_000L, r.drained)
        assertEquals(1_000L, r.totalPaid)
        assertEquals(setOf(500L, 300L, 200L), r.payouts.map { it.amount }.toSet())
        assertEquals(JackpotLotteryDto.STATUS_DRAWN, lottery.status)
        assertEquals(0L, lottery.poolAmount)
    }

    @Test
    fun `drawLottery publishes one LotteryWonEvent per winner with their amount`() {
        val recordingPublisher = RecordingEventPublisher()
        val withPublisher = JackpotLotteryService(
            lotteryPersistence, jackpotService, userService, configService,
            random = kotlin.random.Random(42),
            eventPublisher = recordingPublisher,
        )

        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 1, spent = 100L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 2L, ticketCount = 1, spent = 100L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 3L, ticketCount = 1, spent = 100L),
        )
        (1L..3L).forEach { id ->
            every { userService.getUserByIdForUpdate(id, guildId) } returns
                UserDto(discordId = id, guildId = guildId).apply { socialCredit = 0L }
        }

        withPublisher.drawLottery(guildId)

        assertEquals(3, recordingPublisher.lotteryEvents.size)
        recordingPublisher.lotteryEvents.forEach { e ->
            assertEquals(guildId, e.guildId)
            assertTrue(e.discordId in 1L..3L)
            assertTrue(e.amount in setOf(500L, 300L, 200L))
        }
    }

    private class RecordingEventPublisher : org.springframework.context.ApplicationEventPublisher {
        val lotteryEvents: MutableList<common.events.LotteryWonEvent> = mutableListOf()
        override fun publishEvent(event: org.springframework.context.ApplicationEvent) {}
        override fun publishEvent(event: Any) {
            if (event is common.events.LotteryWonEvent) lotteryEvents.add(event)
        }
    }

    @Test
    fun `cancelLottery refunds buyers and returns seed to the jackpot pool`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_500L,
            status = JackpotLotteryDto.STATUS_OPEN, mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 5, spent = 500L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 2L, ticketCount = 3, spent = 300L),
        )
        val users = (1L..2L).associate { id ->
            id to UserDto(discordId = id, guildId = guildId).apply { socialCredit = 0L }
        }
        users.forEach { (id, u) ->
            every { userService.getUserByIdForUpdate(id, guildId) } returns u
        }

        val r = service.cancelLottery(guildId)
        assertTrue(r is JackpotLotteryService.CancelOutcome.Ok)
        r as JackpotLotteryService.CancelOutcome.Ok
        assertEquals(2, r.refundedUsers)
        assertEquals(800L, r.refundedTotal)
        assertEquals(700L, r.returnedToPool)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 700L) }
        assertEquals(500L, users[1L]!!.socialCredit)
        assertEquals(300L, users[2L]!!.socialCredit)
        assertEquals(JackpotLotteryDto.STATUS_CANCELLED, lottery.status)
    }

    // ---- prizeShares (internal) ----

    @Test
    fun `prizeShares uses fixed schedules for 1 to 4 winners`() {
        assertEquals(listOf(1000L), service.prizeShares(1, 1000L))
        assertEquals(listOf(600L, 400L), service.prizeShares(2, 1000L))
        assertEquals(listOf(500L, 300L, 200L), service.prizeShares(3, 1000L))
        assertEquals(listOf(400L, 300L, 200L, 100L), service.prizeShares(4, 1000L))
    }

    @Test
    fun `prizeShares uses linear taper for 5+ winners and remains a partition`() {
        val shares = service.prizeShares(5, 1500L)
        assertEquals(5, shares.size)
        assertTrue(shares.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(shares.all { it >= 0 })
        assertTrue(shares.sum() <= 1500L)
    }

    // ---- drawWinners (weighted) ----

    @Test
    fun `drawWinners picks the same-weight users uniformly without replacement`() {
        val tickets = listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 10, spent = 0L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 2L, ticketCount = 10, spent = 0L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 3L, ticketCount = 10, spent = 0L),
        )
        val winners = service.drawWinners(tickets, count = 3, random = Random(42))
        assertEquals(3, winners.size)
        assertEquals(3, winners.map { it.discordId }.toSet().size)
    }

    @Test
    fun `drawWinners cannot pick the same user twice`() {
        val tickets = listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 1000, spent = 0L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 2L, ticketCount = 1, spent = 0L),
        )
        val winners = service.drawWinners(tickets, count = 2, random = Random(0))
        assertEquals(2, winners.size)
        assertEquals(2, winners.map { it.discordId }.toSet().size, "no duplicates even when one user dominates weight")
    }

    @Test
    fun `drawWinners returns fewer than count when there aren't enough holders`() {
        val tickets = listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 5, spent = 0L),
        )
        val winners = service.drawWinners(tickets, count = 3, random = Random(1))
        assertEquals(1, winners.size)
        assertNotNull(winners.first())
    }

    // ===================================================================
    // NUMBER_MATCH tests
    // ===================================================================

    @Test
    fun `validatePicks rejects wrong-size, duplicate, or out-of-range picks`() {
        // Wrong size
        assertNotNull(service.validatePicks(listOf(1, 2, 3, 4), pickCount = 5, numberMax = 49))
        // Duplicates
        assertNotNull(service.validatePicks(listOf(1, 2, 3, 3, 5), pickCount = 5, numberMax = 49))
        // Out of range (low)
        assertNotNull(service.validatePicks(listOf(0, 1, 2, 3, 4), pickCount = 5, numberMax = 49))
        // Out of range (high)
        assertNotNull(service.validatePicks(listOf(1, 2, 3, 4, 50), pickCount = 5, numberMax = 49))
        // Valid
        assertEquals(null, service.validatePicks(listOf(1, 13, 27, 33, 49), pickCount = 5, numberMax = 49))
    }

    @Test
    fun `drawNumbers returns count distinct sorted numbers in 1 to max`() {
        val result = service.drawNumbers(max = 49, count = 5, random = Random(123))
        assertEquals(5, result.size)
        assertEquals(5, result.toSet().size, "all numbers must be distinct")
        assertTrue(result.all { it in 1..49 })
        assertEquals(result.sorted(), result, "result is sorted ascending")
    }

    @Test
    fun `computeTierShares applies percentages with floor`() {
        val shares = service.computeTierShares(1000L, intArrayOf(60, 25, 10, 5))
        assertEquals(listOf(600L, 250L, 100L, 50L), shares.toList())
        // 1001 with 60% = 600.6 → floor 600
        val shares2 = service.computeTierShares(1001L, intArrayOf(60, 25, 10, 5))
        assertEquals(listOf(600L, 250L, 100L, 50L), shares2.toList())
    }

    @Test
    fun `parsePicks tolerates null, blank, and bogus entries`() {
        assertEquals(emptyList<Int>(), service.parsePicks(null))
        assertEquals(emptyList<Int>(), service.parsePicks(""))
        assertEquals(emptyList<Int>(), service.parsePicks("   "))
        assertEquals(listOf(1, 2, 3), service.parsePicks("1,2,3"))
        assertEquals(listOf(1, 3), service.parsePicks("1,abc,3"))
    }

    @Test
    fun `openMatchLottery seeds from configured percentage of jackpot pool`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns null
        every { jackpotService.getPool(guildId) } returns 200_000L
        every { jackpotService.resetPool(guildId) } returns 200_000L
        val saved = slot<JackpotLotteryDto>()
        every { lotteryPersistence.upsert(capture(saved)) } answers { saved.captured.also { it.id = 42L } }

        val r = service.openMatchLottery(guildId, ticketPrice = 50L, seedPct = 5L, durationHours = 24)
        assertTrue(r is JackpotLotteryService.OpenOutcome.Ok)
        r as JackpotLotteryService.OpenOutcome.Ok
        assertEquals(10_000L, r.seeded, "5% of 200k = 10k seeded")
        assertEquals(JackpotLotteryDto.MODE_NUMBER_MATCH, saved.captured.mode)
        assertEquals(50L, saved.captured.ticketPrice)
        assertEquals(LotteryHelper.MATCH_PICK_COUNT, saved.captured.pickCount)
        assertEquals(LotteryHelper.MATCH_NUMBER_MAX, saved.captured.numberMax)
        assertEquals(JackpotLotteryDto.STATUS_OPEN, saved.captured.status)
        // Leftover (190k) returned to jackpot.
        verify(exactly = 1) { jackpotService.addToPool(guildId, 190_000L) }
    }

    @Test
    fun `openMatchLottery accepts an empty jackpot for engagement-only prize pool`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns null
        every { jackpotService.getPool(guildId) } returns 0L
        val saved = slot<JackpotLotteryDto>()
        every { lotteryPersistence.upsert(capture(saved)) } answers { saved.captured.also { it.id = 1L } }

        val r = service.openMatchLottery(guildId, ticketPrice = 50L, seedPct = 5L, durationHours = 24)
        assertTrue(r is JackpotLotteryService.OpenOutcome.Ok)
        r as JackpotLotteryService.OpenOutcome.Ok
        assertEquals(0L, r.seeded)
    }

    @Test
    fun `buyMatchTicket rejects invalid picks`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH, pickCount = 5, numberMax = 49,
        )

        val r = service.buyMatchTicket(guildId, discordId = 7L, picks = listOf(1, 2, 3))
        assertTrue(r is JackpotLotteryService.BuyMatchOutcome.InvalidPicks)
    }

    @Test
    fun `buyMatchTicket rejects when no lottery open`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns null

        assertEquals(
            JackpotLotteryService.BuyMatchOutcome.NoOpenLottery,
            service.buyMatchTicket(guildId, discordId = 7L, picks = listOf(1, 2, 3, 4, 5))
        )
    }

    @Test
    fun `buyMatchTicket rejects double-buy by the same user`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH, pickCount = 5, numberMax = 49,
        )
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = 7L, ticketCount = 1, spent = 50L, pickedNumbers = "3,7,11,21,42",
        )

        assertEquals(
            JackpotLotteryService.BuyMatchOutcome.AlreadyBought,
            service.buyMatchTicket(guildId, discordId = 7L, picks = listOf(1, 2, 3, 4, 5))
        )
    }

    @Test
    fun `buyMatchTicket splits ticket revenue 70 percent prize 30 percent jackpot`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 5_000L,
            status = JackpotLotteryDto.STATUS_OPEN, mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
            pickCount = 5, numberMax = 49,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns lottery
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns null
        val user = UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 1_000L }
        every { userService.getUserByIdForUpdate(7L, guildId) } returns user

        val r = service.buyMatchTicket(guildId, discordId = 7L, picks = listOf(1, 13, 27, 33, 49))
        assertTrue(r is JackpotLotteryService.BuyMatchOutcome.Ok)
        r as JackpotLotteryService.BuyMatchOutcome.Ok
        assertEquals(listOf(1, 13, 27, 33, 49), r.pickedNumbers)
        assertEquals(100L, r.totalSpent)
        assertEquals(900L, r.newBalance)
        // Default 30% to jackpot → 30 credits routed; 70 credits to prize pool.
        assertEquals(30L, r.jackpotInflow)
        assertEquals(5_070L, r.newPool)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 30L) }
    }

    @Test
    fun `drawMatchLottery distributes prize pool by tier and rolls remainder back to jackpot`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, poolAmount = 1_000L,
            status = JackpotLotteryDto.STATUS_OPEN, mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
            pickCount = 5, numberMax = 49,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns lottery
        // Tickets crafted so we can compute matches against a controlled draw.
        // We'll inject Random(42) — but for this test we cheat by stubbing
        // drawNumbers via a service spy isn't easy; instead, we craft picks
        // to span every tier and rely on the deterministic Random seed.
        // Use service.drawNumbers(49, 5, Random(42)) once to capture the
        // expected draw, then craft picks accordingly.
        val expectedDraw = service.drawNumbers(49, 5, Random(42))

        // Tickets:
        //  user 1: matches all 5 (tier 5/5)
        //  user 2: matches 4 (replace last pick with non-drawn number)
        //  user 3: matches 3
        //  user 4: matches 2
        //  user 5: matches 1 (no payout)
        val nonDrawn = (1..49).filter { it !in expectedDraw }
        fun ticket(id: Long, picks: List<Int>) = JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = id, ticketCount = 1, spent = 50L,
            pickedNumbers = picks.joinToString(","),
        )

        val tickets = listOf(
            ticket(1L, expectedDraw),
            ticket(2L, expectedDraw.take(4) + nonDrawn[0]),
            ticket(3L, expectedDraw.take(3) + nonDrawn.subList(0, 2)),
            ticket(4L, expectedDraw.take(2) + nonDrawn.subList(0, 3)),
            ticket(5L, expectedDraw.take(1) + nonDrawn.subList(0, 4)),
        )
        every { lotteryPersistence.ticketsByLottery(1L) } returns tickets

        val users = (1L..5L).associate { id ->
            id to UserDto(discordId = id, guildId = guildId).apply { socialCredit = 0L }
        }
        users.forEach { (id, u) ->
            every { userService.getUserByIdForUpdate(id, guildId) } returns u
        }

        val r = service.drawMatchLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawMatchOutcome.Ok)
        r as JackpotLotteryService.DrawMatchOutcome.Ok
        assertEquals(expectedDraw, r.drawnNumbers)
        assertEquals(1_000L, r.drained)

        // Tier shares: 60 / 25 / 10 / 5 of 1000 → 600 / 250 / 100 / 50.
        val byMatches = r.tierPayouts.associate { it.matches to it.share }
        assertEquals(600L, byMatches[5])
        assertEquals(250L, byMatches[4])
        assertEquals(100L, byMatches[3])
        assertEquals(50L, byMatches[2])
        assertEquals(1_000L, r.totalPaid)
        assertEquals(0L, r.rolledBackToJackpot)
        assertEquals(JackpotLotteryDto.STATUS_DRAWN, lottery.status)
        assertEquals(expectedDraw.joinToString(","), lottery.drawnNumbers)
    }

    @Test
    fun `drawMatchLottery rolls empty tier shares back to jackpot`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, poolAmount = 1_000L,
            status = JackpotLotteryDto.STATUS_OPEN, mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
            pickCount = 5, numberMax = 49,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns lottery

        // Single ticket that never matches any drawn number.
        val expectedDraw = service.drawNumbers(49, 5, Random(42))
        val nonDrawn = (1..49).filter { it !in expectedDraw }.take(5)
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 7L, ticketCount = 1, spent = 50L,
                pickedNumbers = nonDrawn.joinToString(","),
            )
        )
        val user = UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 0L }
        every { userService.getUserByIdForUpdate(7L, guildId) } returns user

        val r = service.drawMatchLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawMatchOutcome.Ok)
        r as JackpotLotteryService.DrawMatchOutcome.Ok
        // No tier paid out — entire pool rolls back.
        assertEquals(0L, r.totalPaid)
        assertEquals(1_000L, r.rolledBackToJackpot)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 1_000L) }
    }

    @Test
    fun `drawMatchLottery returns NoTickets when nobody bought today`() {
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
        )
        every { lotteryPersistence.ticketsByLottery(1L) } returns emptyList()

        assertEquals(
            JackpotLotteryService.DrawMatchOutcome.NoTickets,
            service.drawMatchLottery(guildId)
        )
    }

    // ===================================================================
    // BelowMinBuyers participation gate — both modes
    // ===================================================================

    private fun stubMinBuyers(value: Int) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_DAILY_MIN_BUYERS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_DAILY_MIN_BUYERS.configValue,
            value = value.toString(),
            guildId = guildId.toString()
        )
    }

    @Test
    fun `drawLottery returns BelowMinBuyers when distinct buyers below threshold`() {
        stubMinBuyers(2)
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, poolAmount = 1_000L,
            status = JackpotLotteryDto.STATUS_OPEN, mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        // Single buyer with multiple tickets — still below distinct-buyer
        // threshold, so the gate fires regardless of ticket count.
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 5, spent = 250L),
        )

        val r = service.drawLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawOutcome.BelowMinBuyers)
        r as JackpotLotteryService.DrawOutcome.BelowMinBuyers
        assertEquals(1, r.have)
        assertEquals(2, r.need)
        // No payouts written, pool unchanged — caller is expected to
        // cancel + refund.
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `drawLottery proceeds normally at threshold`() {
        stubMinBuyers(2)
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 3, spent = 150L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 2L, ticketCount = 2, spent = 100L),
        )
        val users = (1L..2L).associate { id ->
            id to UserDto(discordId = id, guildId = guildId).apply { socialCredit = 0L }
        }
        users.forEach { (id, u) ->
            every { userService.getUserByIdForUpdate(id, guildId) } returns u
        }

        val r = service.drawLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawOutcome.Ok, "2 distinct buyers passes the gate")
    }

    @Test
    fun `drawLottery threshold of 1 disables the gate (matches pre-PR behaviour)`() {
        stubMinBuyers(1)
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 1, spent = 50L),
        )
        val user = UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 0L }
        every { userService.getUserByIdForUpdate(7L, guildId) } returns user

        val r = service.drawLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawOutcome.Ok, "threshold 1 lets a single buyer through")
    }

    @Test
    fun `drawMatchLottery returns BelowMinBuyers when distinct buyers below threshold`() {
        stubMinBuyers(2)
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)
        } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, poolAmount = 1_000L,
            status = JackpotLotteryDto.STATUS_OPEN, mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
            pickCount = 5, numberMax = 49,
        )
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 7L, ticketCount = 1, spent = 50L,
                pickedNumbers = "1,2,3,4,5",
            ),
        )

        val r = service.drawMatchLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawMatchOutcome.BelowMinBuyers)
        r as JackpotLotteryService.DrawMatchOutcome.BelowMinBuyers
        assertEquals(1, r.have)
        assertEquals(2, r.need)
        // No drawn numbers persisted, no payouts — caller cancels + refunds.
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ===================================================================
    // Participation incentives (TICKET_WEIGHTED only)
    // ===================================================================

    /** Stub a single bulk-buy bonus tier (or clear if buy <= 0). */
    private fun stubBulkTier1(buy: Long, bonus: Long) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY.configValue,
            value = buy.toString(),
            guildId = guildId.toString()
        )
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS.configValue,
            value = bonus.toString(),
            guildId = guildId.toString()
        )
    }

    private fun stubMultTier1(total: Long, bp: Int) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_MULT_TIER1_TOTAL.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_MULT_TIER1_TOTAL.configValue,
            value = total.toString(),
            guildId = guildId.toString()
        )
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_MULT_TIER1_BP.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_MULT_TIER1_BP.configValue,
            value = bp.toString(),
            guildId = guildId.toString()
        )
    }

    private fun stubMilestone1(tickets: Long, pct: Long) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS.configValue,
            value = tickets.toString(),
            guildId = guildId.toString()
        )
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT.configValue,
            value = pct.toString(),
            guildId = guildId.toString()
        )
    }

    private fun openWeightedLottery(pool: Long = 0L): JackpotLotteryDto {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = pool,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        return lottery
    }

    @Test
    fun `buyTickets grants no bulk bonus when tiers are unset`() {
        openWeightedLottery()
        every { userService.getUserByIdForUpdate(7L, guildId) } returns
            UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 10_000L }
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns null

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 10)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
        r as JackpotLotteryService.BuyOutcome.Ok
        assertEquals(0L, r.bonusTicketsGranted)
        assertEquals(0L, r.totalBonusTickets)
        assertTrue(r.milestoneBonuses.isEmpty())
    }

    @Test
    fun `buyTickets grants bulk bonus when a single purchase hits the threshold`() {
        stubBulkTier1(buy = 10L, bonus = 3L)
        openWeightedLottery()
        every { userService.getUserByIdForUpdate(7L, guildId) } returns
            UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 10_000L }
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns null

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 10)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
        r as JackpotLotteryService.BuyOutcome.Ok
        assertEquals(3L, r.bonusTicketsGranted)
        assertEquals(3L, r.totalBonusTickets)
    }

    @Test
    fun `buyTickets splitting buys across 1-ticket purchases earns no bonus (anti-strategy)`() {
        stubBulkTier1(buy = 10L, bonus = 3L)
        openWeightedLottery()
        val user = UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 10_000L }
        every { userService.getUserByIdForUpdate(7L, guildId) } returns user
        val existing = JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 0, spent = 0L)
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns existing

        repeat(10) {
            val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 1)
            assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
            r as JackpotLotteryService.BuyOutcome.Ok
            assertEquals(0L, r.bonusTicketsGranted, "single-ticket buys never hit the bulk threshold")
        }
        assertEquals(10, existing.ticketCount)
        assertEquals(0L, existing.bonusTickets)
    }

    @Test
    fun `buyTickets picks the highest matching bulk tier`() {
        stubBulkTier1(buy = 5L, bonus = 1L)
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_BULK_TIER2_BUY.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_BULK_TIER2_BUY.configValue,
            value = "20",
            guildId = guildId.toString()
        )
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_BULK_TIER2_BONUS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_BULK_TIER2_BONUS.configValue,
            value = "8",
            guildId = guildId.toString()
        )
        openWeightedLottery()
        every { userService.getUserByIdForUpdate(7L, guildId) } returns
            UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 10_000L }
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns null

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 25)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
        r as JackpotLotteryService.BuyOutcome.Ok
        assertEquals(8L, r.bonusTicketsGranted, "tier2 wins; tier1 is subsumed")
    }

    @Test
    fun `effectiveWeight collapses to ticketCount when multiplier tiers empty`() {
        val ticket = JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 12, spent = 0L)
        assertEquals(12L, service.effectiveWeight(ticket, emptyList()))
    }

    @Test
    fun `effectiveWeight adds bonusTickets and multiplier uplift`() {
        val ticket = JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = 7L, ticketCount = 20, spent = 0L,
            bonusTickets = 5L,
        )
        // tier: hold ≥15 → 1.5× (15000 bp). 20 paid * 0.5 = +10 multiplier weight.
        val weight = service.effectiveWeight(ticket, listOf(15L to 15_000))
        assertEquals(20L + 5L + 10L, weight)
    }

    @Test
    fun `drawLottery weights a high-volume buyer more than their raw ticket count`() {
        stubMultTier1(total = 15L, bp = 15_000)  // 1.5×
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 1, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        // Buyer A holds 1 paid ticket (weight 1). Buyer B holds 20 paid
        // tickets, qualifies for 1.5× (effective weight 30). Combined
        // total weight 31; B should win ~30/31 of seeded RNG draws.
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 1L, ticketCount = 1, spent = 100L),
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 2L, ticketCount = 20, spent = 2_000L),
        )
        (1L..2L).forEach { id ->
            every { userService.getUserByIdForUpdate(id, guildId) } returns
                UserDto(discordId = id, guildId = guildId).apply { socialCredit = 0L }
        }

        val r = service.drawLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawOutcome.Ok)
        r as JackpotLotteryService.DrawOutcome.Ok
        assertEquals(1, r.payouts.size)
        // With seed 42 and combined effective weight 31, the first roll
        // lands inside buyer B's slice — this asserts deterministic
        // behaviour, not just probability.
        assertEquals(2L, r.payouts.single().discordId)
    }

    @Test
    fun `buyTickets fires a milestone when guild-wide total crosses the threshold`() {
        stubMilestone1(tickets = 50L, pct = 10L)
        val lottery = openWeightedLottery(pool = 0L)
        every { userService.getUserByIdForUpdate(7L, guildId) } returns
            UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 100_000L }
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns null
        // After upsert, ticketsByLottery reflects this user's new row:
        // running guild-wide ticket count = 50, prevTotal = 0.
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 50, spent = 5_000L),
        )
        every { jackpotService.getPool(guildId) } returns 1_000L
        every { jackpotService.resetPool(guildId) } returns 1_000L

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 50)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
        r as JackpotLotteryService.BuyOutcome.Ok
        assertEquals(1, r.milestoneBonuses.size, "one milestone fires")
        assertEquals(50L, r.milestoneBonuses.single().threshold)
        assertEquals(100L, r.milestoneBonuses.single().creditsAdded, "10% of 1000 = 100")
        assertEquals(50L, lottery.milestonesFired)
        // Pool = cost (5000) + milestone (100).
        assertEquals(5_100L, lottery.poolAmount)
    }

    @Test
    fun `buyTickets does not refire a milestone already recorded on the lottery`() {
        stubMilestone1(tickets = 50L, pct = 10L)
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 5_100L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            milestonesFired = 50L,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { userService.getUserByIdForUpdate(7L, guildId) } returns
            UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 100_000L }
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 50, spent = 5_000L)
        // After this buy: total = 60, well past the 50 threshold but
        // we already fired at 50, so nothing new should trigger.
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 60, spent = 6_000L),
        )

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 10)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
        r as JackpotLotteryService.BuyOutcome.Ok
        assertTrue(r.milestoneBonuses.isEmpty(), "milestone already fired earlier")
        verify(exactly = 0) { jackpotService.resetPool(guildId) }
    }

    @Test
    fun `buyTickets skips a milestone cleanly when jackpot is empty`() {
        stubMilestone1(tickets = 50L, pct = 10L)
        openWeightedLottery(pool = 0L)
        every { userService.getUserByIdForUpdate(7L, guildId) } returns
            UserDto(discordId = 7L, guildId = guildId).apply { socialCredit = 100_000L }
        every { lotteryPersistence.getTicketForUpdate(1L, 7L) } returns null
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = 7L, ticketCount = 50, spent = 5_000L),
        )
        every { jackpotService.getPool(guildId) } returns 0L

        val r = service.buyTickets(guildId, discordId = 7L, ticketCount = 50)
        assertTrue(r is JackpotLotteryService.BuyOutcome.Ok)
        r as JackpotLotteryService.BuyOutcome.Ok
        assertTrue(r.milestoneBonuses.isEmpty(), "empty jackpot delivers no top-up")
        // Purchase itself still succeeded — only the milestone was a no-op.
        verify(exactly = 0) { jackpotService.resetPool(guildId) }
    }

    @Test
    fun `drawLottery still returns BelowMinBuyers when distinct buyers fall short despite bonuses`() {
        stubMinBuyers(2)
        stubBulkTier1(buy = 5L, bonus = 3L)  // bonus active but irrelevant
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 2_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 7L, ticketCount = 10, spent = 1_000L,
                bonusTickets = 3L,
            ),
        )

        val r = service.drawLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawOutcome.BelowMinBuyers,
            "the participation gate is independent of the bonus path")
    }

    @Test
    fun `drawLottery reports bonusTicketsAwarded and highestMilestoneFired in the outcome`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 3_000L,
            winnerCount = 1, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            milestonesFired = 50L,
        )
        every {
            lotteryPersistence.getOpenByGuildAndModeForUpdate(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        } returns lottery
        every { lotteryPersistence.ticketsByLottery(1L) } returns listOf(
            JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 1L, ticketCount = 30, spent = 3_000L, bonusTickets = 8L,
            ),
            JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 2L, ticketCount = 20, spent = 2_000L, bonusTickets = 3L,
            ),
        )
        (1L..2L).forEach { id ->
            every { userService.getUserByIdForUpdate(id, guildId) } returns
                UserDto(discordId = id, guildId = guildId).apply { socialCredit = 0L }
        }

        val r = service.drawLottery(guildId)
        assertTrue(r is JackpotLotteryService.DrawOutcome.Ok)
        r as JackpotLotteryService.DrawOutcome.Ok
        assertEquals(11L, r.bonusTicketsAwarded, "8 + 3 across both buyers")
        assertEquals(50L, r.highestMilestoneFired)
    }
}
