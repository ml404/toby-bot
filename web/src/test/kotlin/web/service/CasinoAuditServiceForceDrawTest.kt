package web.service

import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.casino.CasinoAdminService
import database.service.economy.JackpotService
import database.service.guild.ConfigService
import database.service.lottery.JackpotLotteryService
import database.service.lottery.JackpotLotteryService.DrawMatchOutcome
import database.service.lottery.JackpotLotteryService.DrawOutcome
import database.service.lottery.JackpotLotteryService.OpenOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises the [CasinoAuditService.forceDailyDraw] branch matrix that the
 * sibling [CasinoAuditServiceTest] leaves at the structural level: both
 * lottery modes (NUMBER_MATCH / WEIGHTED) crossed with each draw outcome
 * and each open outcome. All dependencies are mocked, so this is the
 * web-side dispatch logic only — the lottery engine itself is tested in
 * the database module.
 */
class CasinoAuditServiceForceDrawTest {

    private lateinit var configService: ConfigService
    private lateinit var jackpotService: JackpotService
    private lateinit var casinoAdminService: CasinoAdminService
    private lateinit var lottery: JackpotLotteryService
    private lateinit var authorizer: ModerationAuthorizer
    private lateinit var service: CasinoAuditService

    private val guildId = 7777L
    private val actor = 11L

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        casinoAdminService = mockk(relaxed = true)
        lottery = mockk(relaxed = true)
        authorizer = mockk(relaxed = true)
        service = CasinoAuditService(configService, jackpotService, casinoAdminService, lottery, authorizer)
        every { authorizer.canModerate(actor, guildId) } returns true
    }

    private fun useWeightedMode() {
        every {
            configService.getConfigByName(Configurations.LOTTERY_DAILY_MODE.configValue, guildId.toString())
        } returns ConfigDto(Configurations.LOTTERY_DAILY_MODE.configValue, "WEIGHTED", guildId.toString())
    }

    private fun openOk(seeded: Long = 500L) = OpenOutcome.Ok(mockk(relaxed = true), seeded = seeded)

    // --- NUMBER_MATCH mode (the default) --------------------------------

    @Test
    fun `number-match draw Ok then open Ok carries prior results forward`() {
        every { lottery.drawMatchLottery(guildId) } returns
            DrawMatchOutcome.Ok(drawnNumbers = listOf(1, 2, 3, 4, 5), tierPayouts = emptyList(), totalPaid = 800L, drained = 1000L, rolledBackToJackpot = 200L)
        every { lottery.openMatchLottery(any(), any(), any(), any()) } returns openOk(seeded = 750L)

        val r = service.forceDailyDraw(actor, guildId)

        assertNull(r.error)
        assertTrue(r.drewPrior)
        assertEquals(800L, r.priorTotalPaid)
        assertEquals(200L, r.priorRolledBack)
        assertEquals(listOf(1, 2, 3, 4, 5), r.priorDrawn)
        assertTrue(r.openedNew)
        assertEquals(750L, r.newSeeded)
    }

    @Test
    fun `number-match draw NoTickets cancels then opens`() {
        every { lottery.drawMatchLottery(guildId) } returns DrawMatchOutcome.NoTickets
        every { lottery.openMatchLottery(any(), any(), any(), any()) } returns openOk()

        val r = service.forceDailyDraw(actor, guildId)

        assertTrue(r.drewPrior)
        assertTrue(r.openedNew)
        verify { lottery.cancelMatchLottery(guildId) }
    }

    @Test
    fun `number-match draw BelowMinBuyers cancels and surfaces buyer counts`() {
        every { lottery.drawMatchLottery(guildId) } returns DrawMatchOutcome.BelowMinBuyers(have = 1, need = 2)
        every { lottery.openMatchLottery(any(), any(), any(), any()) } returns OpenOutcome.EmptyPool

        val r = service.forceDailyDraw(actor, guildId)

        assertNull(r.error)
        assertTrue(r.drewPrior)
        assertTrue(r.priorBelowMinBuyers)
        assertEquals(1, r.priorBuyersHave)
        assertEquals(2, r.priorBuyersNeed)
        assertFalse(r.openedNew)
        verify { lottery.cancelMatchLottery(guildId) }
    }

    @Test
    fun `number-match no open lottery then AlreadyOpen reports the conflict`() {
        every { lottery.drawMatchLottery(guildId) } returns DrawMatchOutcome.NoOpenLottery
        every { lottery.openMatchLottery(any(), any(), any(), any()) } returns OpenOutcome.AlreadyOpen

        val r = service.forceDailyDraw(actor, guildId)

        assertFalse(r.drewPrior)
        assertFalse(r.openedNew)
        assertEquals("A daily lottery is already open — close it first.", r.error)
    }

    @Test
    fun `open InvalidParams propagates the reason`() {
        every { lottery.drawMatchLottery(guildId) } returns DrawMatchOutcome.NoOpenLottery
        every { lottery.openMatchLottery(any(), any(), any(), any()) } returns OpenOutcome.InvalidParams("bad seed")

        val r = service.forceDailyDraw(actor, guildId)

        assertEquals("bad seed", r.error)
        assertFalse(r.openedNew)
    }

    // --- WEIGHTED mode ---------------------------------------------------

    @Test
    fun `weighted draw Ok then open Ok rolls back the unpaid remainder`() {
        useWeightedMode()
        every { lottery.drawLottery(guildId) } returns
            DrawOutcome.Ok(payouts = emptyList(), totalPaid = 600L, drained = 1000L)
        every { lottery.openLottery(any(), any(), any(), any(), any()) } returns openOk(seeded = 300L)

        val r = service.forceDailyDraw(actor, guildId)

        assertNull(r.error)
        assertTrue(r.drewPrior)
        assertEquals(600L, r.priorTotalPaid)
        assertEquals(400L, r.priorRolledBack) // drained - totalPaid
        assertTrue(r.openedNew)
        assertEquals(300L, r.newSeeded)
    }

    @Test
    fun `weighted draw NoTickets cancels the weighted lottery`() {
        useWeightedMode()
        every { lottery.drawLottery(guildId) } returns DrawOutcome.NoTickets
        every { lottery.openLottery(any(), any(), any(), any(), any()) } returns openOk()

        val r = service.forceDailyDraw(actor, guildId)

        assertTrue(r.drewPrior)
        verify { lottery.cancelLottery(guildId) }
    }

    @Test
    fun `weighted draw BelowMinBuyers cancels and reports counts`() {
        useWeightedMode()
        every { lottery.drawLottery(guildId) } returns DrawOutcome.BelowMinBuyers(have = 0, need = 3)
        every { lottery.openLottery(any(), any(), any(), any(), any()) } returns openOk()

        val r = service.forceDailyDraw(actor, guildId)

        assertTrue(r.priorBelowMinBuyers)
        assertEquals(3, r.priorBuyersNeed)
        verify { lottery.cancelLottery(guildId) }
    }

    @Test
    fun `weighted no open lottery still opens a fresh draw`() {
        useWeightedMode()
        every { lottery.drawLottery(guildId) } returns DrawOutcome.NoOpenLottery
        every { lottery.openLottery(any(), any(), any(), any(), any()) } returns openOk(seeded = 42L)

        val r = service.forceDailyDraw(actor, guildId)

        assertFalse(r.drewPrior)
        assertTrue(r.openedNew)
        assertEquals(42L, r.newSeeded)
    }
}
