package web.controller

import database.dto.JackpotLotteryDto
import database.service.JackpotLotteryService.BuyMatchOutcome
import database.service.JackpotLotteryService.BuyOutcome
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.CasinoPageContext
import web.service.EconomyWebService
import web.service.LotteryWebService

/**
 * Web HTTP-shape tests for the lottery controller. Mirrors the
 * `SlotsControllerTest` style: covers each outcome's status code +
 * response payload so refactors to the underlying service can't
 * accidentally break the buy-flow contract.
 */
class LotteryControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var lotteryWebService: LotteryWebService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var user: OAuth2User
    private lateinit var controller: LotteryController

    @BeforeEach
    fun setup() {
        lotteryWebService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = LotteryController(lotteryWebService, economyWebService, pageContext)
    }

    // ---- /match/buy -------------------------------------------

    @Test
    fun `buyMatch ok returns 200 with picks, balance, pool and jackpot inflow`() {
        every { lotteryWebService.buyMatch(guildId, discordId, listOf(1, 13, 27, 33, 49)) } returns
            BuyMatchOutcome.Ok(
                pickedNumbers = listOf(1, 13, 27, 33, 49),
                totalSpent = 50L,
                newBalance = 950L,
                newPool = 1_035L,
                jackpotInflow = 15L,
            )

        val response = controller.buyMatch(
            guildId, BuyMatchRequest(picks = listOf(1, 13, 27, 33, 49)), user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertNull(body.error)
        assertEquals(listOf(1, 13, 27, 33, 49), body.pickedNumbers)
        assertEquals(50L, body.totalSpent)
        assertEquals(950L, body.newBalance)
        assertEquals(1_035L, body.newPool)
        assertEquals(15L, body.jackpotInflow)
    }

    @Test
    fun `buyMatch invalid picks returns 400 with the validation reason`() {
        every { lotteryWebService.buyMatch(guildId, discordId, any()) } returns
            BuyMatchOutcome.InvalidPicks("must select exactly 5 numbers")

        val response = controller.buyMatch(guildId, BuyMatchRequest(picks = listOf(1, 2, 3)), user)

        assertEquals(400, response.statusCode.value())
        val body = response.body!!
        assertEquals(false, body.ok)
        assertNotNull(body.error)
        assertTrue(body.error!!.contains("must select exactly 5 numbers"))
    }

    @Test
    fun `buyMatch insufficient credits returns 400 with the shortfall`() {
        every { lotteryWebService.buyMatch(guildId, discordId, any()) } returns
            BuyMatchOutcome.Insufficient(have = 30L, need = 50L)

        val response = controller.buyMatch(
            guildId, BuyMatchRequest(picks = listOf(1, 2, 3, 4, 5)), user
        )

        assertEquals(400, response.statusCode.value())
        val body = response.body!!
        assertEquals(false, body.ok)
        assertTrue(body.error!!.contains("30") && body.error!!.contains("50"))
    }

    @Test
    fun `buyMatch no open lottery returns 404`() {
        every { lotteryWebService.buyMatch(guildId, discordId, any()) } returns
            BuyMatchOutcome.NoOpenLottery

        val response = controller.buyMatch(
            guildId, BuyMatchRequest(picks = listOf(1, 2, 3, 4, 5)), user
        )

        assertEquals(404, response.statusCode.value())
        val body = response.body!!
        assertEquals(false, body.ok)
    }

    @Test
    fun `buyMatch already-bought returns 400`() {
        every { lotteryWebService.buyMatch(guildId, discordId, any()) } returns
            BuyMatchOutcome.AlreadyBought

        val response = controller.buyMatch(
            guildId, BuyMatchRequest(picks = listOf(1, 2, 3, 4, 5)), user
        )

        assertEquals(400, response.statusCode.value())
        val body = response.body!!
        assertEquals(false, body.ok)
        assertTrue(body.error!!.contains("already"))
    }

    // ---- /weighted/buy ----------------------------------------

    @Test
    fun `buyWeighted ok returns 200 with ticket count and pool`() {
        every { lotteryWebService.buyWeighted(guildId, discordId, 3) } returns
            BuyOutcome.Ok(
                ticketCount = 3,
                totalSpent = 300L,
                newBalance = 700L,
                newPool = 5_300L,
            )

        val response = controller.buyWeighted(guildId, BuyWeightedRequest(count = 3), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(3, body.ticketCount)
        assertEquals(300L, body.totalSpent)
        assertEquals(700L, body.newBalance)
        assertEquals(5_300L, body.newPool)
    }

    @Test
    fun `buyWeighted no open lottery returns 404`() {
        every { lotteryWebService.buyWeighted(guildId, discordId, 1) } returns
            BuyOutcome.NoOpenLottery

        val response = controller.buyWeighted(guildId, BuyWeightedRequest(count = 1), user)

        assertEquals(404, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
    }

    @Test
    fun `buyWeighted invalid count returns 400`() {
        every { lotteryWebService.buyWeighted(guildId, discordId, 0) } returns
            BuyOutcome.InvalidCount(0)

        val response = controller.buyWeighted(guildId, BuyWeightedRequest(count = 0), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
    }

    @Test
    fun `buyWeighted insufficient credits returns 400 with shortfall`() {
        every { lotteryWebService.buyWeighted(guildId, discordId, 50) } returns
            BuyOutcome.Insufficient(have = 100L, need = 5_000L)

        val response = controller.buyWeighted(guildId, BuyWeightedRequest(count = 50), user)

        assertEquals(400, response.statusCode.value())
        val body = response.body!!
        assertTrue(body.error!!.contains("100") && body.error!!.contains("5000"))
    }

    // ---- LotteryViewModel.from --------------------------------

    @Test
    fun `LotteryViewModel parses drawn numbers and computes my matches`() {
        val open = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 50L, poolAmount = 1_000L,
            status = JackpotLotteryDto.STATUS_DRAWN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
            pickCount = 5, numberMax = 49,
            drawnNumbers = "3,7,11,21,42",
        )
        val ticket = database.dto.JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = discordId, ticketCount = 1, spent = 50L,
            pickedNumbers = "3,7,11,33,49",
        )
        val snap = LotteryWebService.LotteryPageSnapshot(
            dailyOpen = null,
            dailyLatestDrawn = open,
            dailyMyTicket = ticket,
            dailyTicketBuyers = 1,
            weightedOpen = null,
            weightedMyTicket = null,
            weightedTopHolders = emptyList(),
            weightedTotalTickets = 0L,
            pickCount = 5,
            numberMax = 49,
            tierPercents = listOf(60, 25, 10, 5),
            revenueJackpotPct = 30L,
        )

        val vm = LotteryViewModel.from(snap)
        assertNotNull(vm.daily)
        val daily = vm.daily!!
        assertEquals(listOf(3, 7, 11, 21, 42), daily.drawnNumbers)
        assertEquals(listOf(3, 7, 11, 33, 49), daily.myPicks)
        assertEquals(3, daily.myMatches, "matched 3, 7, 11")
        assertEquals(false, daily.isOpen)
    }

    @Test
    fun `LotteryViewModel falls back gracefully when no daily exists`() {
        val snap = LotteryWebService.LotteryPageSnapshot(
            dailyOpen = null,
            dailyLatestDrawn = null,
            dailyMyTicket = null,
            dailyTicketBuyers = 0,
            weightedOpen = null,
            weightedMyTicket = null,
            weightedTopHolders = emptyList(),
            weightedTotalTickets = 0L,
            pickCount = 5,
            numberMax = 49,
            tierPercents = listOf(60, 25, 10, 5),
            revenueJackpotPct = 30L,
        )

        val vm = LotteryViewModel.from(snap)
        assertNull(vm.daily)
        assertNull(vm.weighted)
    }
}
