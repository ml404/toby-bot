package database.service

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
    private lateinit var service: JackpotLotteryService

    @BeforeEach
    fun setup() {
        lotteryPersistence = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        service = JackpotLotteryService(
            lotteryPersistence,
            jackpotService,
            userService,
            random = Random(42),
        )
    }

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
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, status = JackpotLotteryDto.STATUS_OPEN
        )

        val r = service.openLottery(guildId, ticketPrice = 100L, durationHours = 24, winnerCount = 3, drainPct = 1.0)
        assertEquals(JackpotLotteryService.OpenOutcome.AlreadyOpen, r)
    }

    @Test
    fun `openLottery rejects on empty pool`() {
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns null
        every { jackpotService.getPool(guildId) } returns 0L

        val r = service.openLottery(guildId, ticketPrice = 100L, durationHours = 24, winnerCount = 3, drainPct = 1.0)
        assertEquals(JackpotLotteryService.OpenOutcome.EmptyPool, r)
    }

    @Test
    fun `openLottery seeds the lottery from a fraction of the jackpot pool`() {
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns null
        every { jackpotService.getPool(guildId) } returns 110_000L
        every { jackpotService.resetPool(guildId) } returns 110_000L
        val saved = slot<JackpotLotteryDto>()
        every { lotteryPersistence.upsert(capture(saved)) } answers { saved.captured.also { it.id = 99L } }

        val r = service.openLottery(guildId, ticketPrice = 100L, durationHours = 48, winnerCount = 3, drainPct = 0.5)
        assertTrue(r is JackpotLotteryService.OpenOutcome.Ok)
        r as JackpotLotteryService.OpenOutcome.Ok
        assertEquals(55_000L, r.seeded)
        assertEquals(JackpotLotteryDto.STATUS_OPEN, saved.captured.status)
        assertEquals(100L, saved.captured.ticketPrice)
        assertEquals(3, saved.captured.winnerCount)
        // Leftover (55k) returned to the jackpot.
        verify(exactly = 1) { jackpotService.addToPool(guildId, 55_000L) }
    }

    @Test
    fun `buyTickets rejects when no lottery is open`() {
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns null

        assertEquals(
            JackpotLotteryService.BuyOutcome.NoOpenLottery,
            service.buyTickets(guildId, discordId = 7L, ticketCount = 5)
        )
    }

    @Test
    fun `buyTickets rejects when user can't afford`() {
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns JackpotLotteryDto(
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
            status = JackpotLotteryDto.STATUS_OPEN
        )
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns lottery
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
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns null
        assertEquals(JackpotLotteryService.DrawOutcome.NoOpenLottery, service.drawLottery(guildId))
    }

    @Test
    fun `drawLottery rejects when no tickets sold`() {
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, status = JackpotLotteryDto.STATUS_OPEN
        )
        every { lotteryPersistence.ticketsByLottery(1L) } returns emptyList()
        assertEquals(JackpotLotteryService.DrawOutcome.NoTickets, service.drawLottery(guildId))
    }

    @Test
    fun `drawLottery splits the pool 50-30-20 across three winners`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN
        )
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns lottery
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
        // Schedule for 3 winners is 50/30/20.
        assertEquals(setOf(500L, 300L, 200L), r.payouts.map { it.amount }.toSet())
        assertEquals(JackpotLotteryDto.STATUS_DRAWN, lottery.status)
        assertEquals(0L, lottery.poolAmount)
    }

    @Test
    fun `cancelLottery refunds buyers and returns seed to the jackpot pool`() {
        val lottery = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_500L,
            status = JackpotLotteryDto.STATUS_OPEN
        )
        every { lotteryPersistence.getOpenByGuildForUpdate(guildId) } returns lottery
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
        // pool_amount=1500, refunded 800 → 700 returned to the jackpot pool.
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
        // Strictly decreasing, all positive, sum <= pool (rounding remainder
        // returns to the pool in drawLottery).
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
        // No duplicates.
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
}
