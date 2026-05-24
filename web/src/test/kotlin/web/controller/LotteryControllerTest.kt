package web.controller

import database.dto.JackpotLotteryDto
import database.service.lottery.JackpotLotteryService.BuyMatchOutcome
import database.service.lottery.JackpotLotteryService.BuyOutcome
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

    @Test
    fun `buyWeighted ok surfaces bonusTicketsGranted and totalBonusTickets when service awards them`() {
        // Regression guard for the silent-drop bug: the service already
        // awards bonus tickets on bulk-qualifying buys, but the web
        // response originally stripped them, leaving the client with
        // no way to tell a +3 bonus from a +0 buy.
        every { lotteryWebService.buyWeighted(guildId, discordId, 10) } returns
            BuyOutcome.Ok(
                ticketCount = 10,
                totalSpent = 1_000L,
                newBalance = 9_000L,
                newPool = 2_000L,
                bonusTicketsGranted = 3L,
                totalBonusTickets = 3L,
            )

        val response = controller.buyWeighted(guildId, BuyWeightedRequest(count = 10), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(3L, body.bonusTicketsGranted)
        assertEquals(3L, body.totalBonusTickets)
        assertNull(body.milestoneBonuses, "no milestones fired → no list in the response")
    }

    @Test
    fun `buyWeighted ok carries milestoneBonuses when the service fired any`() {
        // Each milestone in the response carries its threshold and the
        // credit top-up; the client toasts one per entry.
        every { lotteryWebService.buyWeighted(guildId, discordId, 50) } returns
            BuyOutcome.Ok(
                ticketCount = 50,
                totalSpent = 5_000L,
                newBalance = 5_000L,
                newPool = 5_100L,
                bonusTicketsGranted = 0L,
                totalBonusTickets = 0L,
                milestoneBonuses = listOf(
                    database.service.lottery.JackpotLotteryService.MilestoneBonus(threshold = 50L, creditsAdded = 100L),
                ),
            )

        val response = controller.buyWeighted(guildId, BuyWeightedRequest(count = 50), user)

        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(1, body.milestoneBonuses?.size)
        assertEquals(50L, body.milestoneBonuses?.first()?.threshold)
        assertEquals(100L, body.milestoneBonuses?.first()?.creditsAdded)
    }

    @Test
    fun `buyWeighted ok omits bonus fields cleanly when nothing was awarded`() {
        // Base case: a normal buy with no bulk-tier qualification and
        // no milestone crossings should leave all three new fields
        // null so the JS toast falls back to its base copy without
        // printing "+0 bonus from bulk-buy" garbage.
        every { lotteryWebService.buyWeighted(guildId, discordId, 1) } returns
            BuyOutcome.Ok(
                ticketCount = 1,
                totalSpent = 100L,
                newBalance = 9_900L,
                newPool = 1_100L,
            )

        val response = controller.buyWeighted(guildId, BuyWeightedRequest(count = 1), user)

        val body = response.body!!
        assertNull(body.bonusTicketsGranted, "no bulk bonus → null, not zero")
        assertNull(body.totalBonusTickets, "no cumulative bonus → null, not zero")
        assertNull(body.milestoneBonuses, "no milestones → null, not empty list")
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
    fun `LotteryViewModel surfaces bulk tiers in the active rules list but exposes no per-viewer hint`() {
        // Bulk bonus is per-purchase: a single buy must satisfy
        // tier.buy on its own, so holding 4 tickets doesn't shrink
        // the "buy at least 10" threshold to "buy 6 more". An older
        // implementation computed gap = tier.buy - myTickets and
        // produced misleading copy — this test pins the contract that
        // the bulk lever exposes ONLY the active-rules list, no
        // personalised "buy gap more" hint.
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
        val panel = vm.weighted!!.incentives
        assertNotNull(panel)
        // Active rules cover the per-purchase requirement for every
        // configured tier.
        assertEquals(2, panel!!.bulkTiers.size)
        assertEquals(10L, panel.bulkTiers.first().buy)
        // The panel shape has no `nextBulkHint` field at all — Kotlin
        // would fail compilation if a refactor re-introduced one.
        // Multiplier / milestone hints stay correctly cumulative.
        assertNull(panel.nextMultiplierHint, "no multiplier tiers configured → no multiplier hint")
        assertNull(panel.milestoneProgress, "no milestones configured → no progress")
    }

    @Test
    fun `LotteryViewModel still surfaces bulk tiers regardless of player ticket count`() {
        // Regression guard against a future refactor that conditions
        // bulk-tier visibility on myTickets (e.g. only showing tiers
        // the player hasn't yet "qualified for"). All configured tiers
        // must surface to every viewer — that's the whole point of
        // exposing the rules.
        val incentives = web.view.LotteryIncentivesView(
            bulkTiers = listOf(
                web.view.BulkBonusTierView(buy = 10L, bonus = 3L),
                web.view.BulkBonusTierView(buy = 25L, bonus = 8L),
            ),
            multiplierTiers = emptyList(),
            poolMilestones = emptyList(),
        )
        val panel = LotteryViewModel.from(
            weightedSnapshot(myTickets = 30, incentives = incentives)
        ).weighted!!.incentives!!
        assertEquals(2, panel.bulkTiers.size)
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
    fun `LotteryViewModel TopHolder exposes bonusTickets from the snapshot`() {
        // The view-model layer must forward bonus tickets so the
        // template can render the "X paid + Y bonus" breakdown on the
        // top-holders list. Without this, the controller's TopHolder
        // would always present 0 bonus regardless of what the service
        // returned.
        val snap = LotteryWebService.LotteryPageSnapshot(
            dailyOpen = null,
            dailyLatestDrawn = null,
            dailyMyTicket = null,
            dailyTicketBuyers = 0,
            weightedOpen = JackpotLotteryDto(
                id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 0L,
                winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
                mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            ),
            weightedMyTicket = null,
            weightedTopHolders = listOf(
                LotteryWebService.TopHolder(
                    discordId = 7L,
                    ticketCount = 10,
                    bonusTickets = 3L,
                    name = "Whale",
                    avatarUrl = null,
                    title = null,
                ),
            ),
            weightedTotalTickets = 13L,
            pickCount = 5,
            numberMax = 49,
            tierPercents = listOf(60, 25, 10, 5),
            revenueJackpotPct = 30L,
            dailyMode = "WEIGHTED",
            dailyEnabled = true,
        )

        val vm = LotteryViewModel.from(snap)
        val holder = vm.weighted!!.topHolders.single()
        assertEquals(10, holder.ticketCount)
        assertEquals(3L, holder.bonusTickets)
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
