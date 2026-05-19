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
            dailyMode = "NUMBER_MATCH",
            dailyEnabled = true,
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
            dailyMode = "NUMBER_MATCH",
            dailyEnabled = true,
        )

        val vm = LotteryViewModel.from(snap)
        assertNull(vm.daily)
        assertNull(vm.weighted)
    }

    // ---- LotteryViewModel.from — Participation incentives panel ------

    private fun weightedSnapshot(
        myTickets: Int = 0,
        myBonusTickets: Long = 0L,
        totalTickets: Long = 0L,
        milestonesFired: Long = 0L,
        incentives: web.view.LotteryIncentivesView = web.view.LotteryIncentivesView.empty(),
    ): LotteryWebService.LotteryPageSnapshot {
        val weighted = JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            milestonesFired = milestonesFired,
        )
        val myTicket = if (myTickets > 0 || myBonusTickets > 0L) {
            database.dto.JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = discordId,
                ticketCount = myTickets, spent = myTickets * 100L,
                bonusTickets = myBonusTickets,
            )
        } else null
        return LotteryWebService.LotteryPageSnapshot(
            dailyOpen = null,
            dailyLatestDrawn = null,
            dailyMyTicket = null,
            dailyTicketBuyers = 0,
            weightedOpen = weighted,
            weightedMyTicket = myTicket,
            weightedTopHolders = emptyList(),
            weightedTotalTickets = totalTickets,
            pickCount = 5,
            numberMax = 49,
            tierPercents = listOf(60, 25, 10, 5),
            revenueJackpotPct = 30L,
            dailyMode = "WEIGHTED",
            dailyEnabled = true,
            weightedIncentives = incentives,
        )
    }

    @Test
    fun `LotteryViewModel hides incentives panel when no tiers are configured`() {
        // Default empty incentives → no panel rendered. The template
        // checks `weighted.incentives != null` to decide whether to
        // emit the section at all.
        val vm = LotteryViewModel.from(weightedSnapshot())
        assertNull(vm.weighted!!.incentives)
    }

    @Test
    fun `LotteryViewModel surfaces active rules when at least one tier is configured`() {
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = listOf(
                web.view.BulkBonusTierView(buy = 10L, bonus = 3L),
                web.view.BulkBonusTierView(buy = 25L, bonus = 8L),
            ),
            multiplierTiers = emptyList(),
            poolMilestones = emptyList(),
        )
        val vm = LotteryViewModel.from(weightedSnapshot(incentives = incentives))
        val panel = vm.weighted!!.incentives
        assertNotNull(panel)
        assertEquals(2, panel!!.bulkTiers.size)
        assertEquals(10L, panel.bulkTiers.first().buy)
        assertTrue(panel.multiplierTiers.isEmpty())
        assertTrue(panel.poolMilestones.isEmpty())
    }

    @Test
    fun `LotteryViewModel computes nextBulkHint as the gap to the next unmatched tier`() {
        // Player holds 4 tickets, lowest tier requires 10. Hint
        // should surface "buy 6 more for +3 free". The +1 tier sits
        // above 4 so the next-tier rule fires there.
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = listOf(
                web.view.BulkBonusTierView(buy = 10L, bonus = 3L),
                web.view.BulkBonusTierView(buy = 25L, bonus = 8L),
            ),
            multiplierTiers = emptyList(),
            poolMilestones = emptyList(),
        )
        val vm = LotteryViewModel.from(
            weightedSnapshot(myTickets = 4, incentives = incentives)
        )
        val hint = vm.weighted!!.incentives!!.nextBulkHint
        assertNotNull(hint)
        assertEquals(6L, hint!!.gap)
        assertEquals(3L, hint.bonus)
        assertEquals(10L, hint.threshold)
    }

    @Test
    fun `LotteryViewModel returns null nextBulkHint when player already holds top tier`() {
        // Player holds 30 tickets; tier ceiling is 25. No further
        // "buy N more" hint to dangle — return null.
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = listOf(
                web.view.BulkBonusTierView(buy = 10L, bonus = 3L),
                web.view.BulkBonusTierView(buy = 25L, bonus = 8L),
            ),
            multiplierTiers = emptyList(),
            poolMilestones = emptyList(),
        )
        val vm = LotteryViewModel.from(
            weightedSnapshot(myTickets = 30, incentives = incentives)
        )
        assertNull(vm.weighted!!.incentives!!.nextBulkHint)
    }

    @Test
    fun `LotteryViewModel formats nextMultiplierHint with a 2-decimal multiplier`() {
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = emptyList(),
            multiplierTiers = listOf(
                web.view.MultiplierTierView(total = 15L, bp = 15_000),
            ),
            poolMilestones = emptyList(),
        )
        val vm = LotteryViewModel.from(
            weightedSnapshot(myTickets = 9, incentives = incentives)
        )
        val hint = vm.weighted!!.incentives!!.nextMultiplierHint
        assertNotNull(hint)
        assertEquals(6L, hint!!.gap)
        // The hint precomputes the human "1.50×" string so the
        // template doesn't have to massage bp at render time.
        assertEquals("1.50×", hint.multiplier)
    }

    @Test
    fun `LotteryViewModel reports the next-to-fire milestone with current vs threshold`() {
        // Guild-wide 42 tickets sold; milestones at 50 and 100, none
        // fired yet. Progress bar should show the 50 tier with
        // current=42, threshold=50.
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = emptyList(),
            multiplierTiers = emptyList(),
            poolMilestones = listOf(
                web.view.PoolMilestoneView(tickets = 50L, pct = 10L),
                web.view.PoolMilestoneView(tickets = 100L, pct = 5L),
            ),
        )
        val vm = LotteryViewModel.from(
            weightedSnapshot(totalTickets = 42L, incentives = incentives)
        )
        val progress = vm.weighted!!.incentives!!.milestoneProgress
        assertNotNull(progress)
        assertEquals(42L, progress!!.currentTickets)
        assertEquals(50L, progress.thresholdTickets)
        assertEquals(10L, progress.pct)
    }

    @Test
    fun `LotteryViewModel skips already-fired milestones when picking the next one`() {
        // 50 already fired (recorded on lottery.milestonesFired);
        // even if current ticket count is still below the 100
        // threshold, the next-to-fire is 100, not 50.
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = emptyList(),
            multiplierTiers = emptyList(),
            poolMilestones = listOf(
                web.view.PoolMilestoneView(tickets = 50L, pct = 10L),
                web.view.PoolMilestoneView(tickets = 100L, pct = 5L),
            ),
        )
        val vm = LotteryViewModel.from(
            weightedSnapshot(totalTickets = 60L, milestonesFired = 50L, incentives = incentives)
        )
        val progress = vm.weighted!!.incentives!!.milestoneProgress
        assertNotNull(progress)
        assertEquals(100L, progress!!.thresholdTickets)
    }

    @Test
    fun `LotteryViewModel returns null milestoneProgress when no future milestone exists`() {
        // Both milestones already fired — nothing further to chase.
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = emptyList(),
            multiplierTiers = emptyList(),
            poolMilestones = listOf(
                web.view.PoolMilestoneView(tickets = 50L, pct = 10L),
                web.view.PoolMilestoneView(tickets = 100L, pct = 5L),
            ),
        )
        val vm = LotteryViewModel.from(
            weightedSnapshot(totalTickets = 120L, milestonesFired = 100L, incentives = incentives)
        )
        assertNull(vm.weighted!!.incentives!!.milestoneProgress)
    }

    @Test
    fun `LotteryViewModel exposes the player's bonus tickets on the weighted view`() {
        // Bonus tickets accumulated from past bulk buys must be
        // visible to the template so the "X paid + Y bonus" copy can
        // render. Untouched when the player has no bonus.
        val vm = LotteryViewModel.from(
            weightedSnapshot(myTickets = 10, myBonusTickets = 3L)
        )
        assertEquals(3L, vm.weighted!!.myBonusTickets)
    }
}
