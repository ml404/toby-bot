package web.service

import database.service.casino.CasinoAdminService
import database.service.guild.ConfigService
import database.service.lottery.JackpotLotteryService
import database.service.economy.JackpotService
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
 * Unit tests for the jackpot/lottery admin slice carved out of
 * ModerationWebService. Covers the authorization gate plus each
 * outcome branch for [CasinoAuditService.resetJackpotPool] and
 * [CasinoAuditService.refundJackpotFromUser]. Force-draw is
 * exercised at the structural level only (mode dispatch + open
 * outcomes); the underlying lottery service has its own dedicated
 * tests.
 */
class CasinoAuditServiceTest {

    private lateinit var configService: ConfigService
    private lateinit var jackpotService: JackpotService
    private lateinit var casinoAdminService: CasinoAdminService
    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var authorizer: ModerationAuthorizer
    private lateinit var service: CasinoAuditService

    private val guildId = 7777L
    private val actor = 11L
    private val source = 22L

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        casinoAdminService = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        authorizer = mockk(relaxed = true)
        service = CasinoAuditService(
            configService,
            jackpotService,
            casinoAdminService,
            jackpotLotteryService,
            authorizer,
        )
    }

    // ---------------- getJackpotPool ----------------

    @Test
    fun `getJackpotPool returns the current pool without an auth check`() {
        every { jackpotService.getPool(guildId) } returns 123_456L
        assertEquals(123_456L, service.getJackpotPool(guildId))
        verify(exactly = 0) { authorizer.canModerate(any(), any()) }
    }

    // ---------------- resetJackpotPool ----------------

    @Test
    fun `resetJackpotPool refuses non-moderators and surfaces the current pool`() {
        every { authorizer.canModerate(actor, guildId) } returns false
        every { jackpotService.getPool(guildId) } returns 999L

        val result = service.resetJackpotPool(actor, guildId)

        assertEquals(CasinoAuditService.NOT_ALLOWED, result.error)
        assertEquals(0L, result.drained)
        assertEquals(999L, result.newPool)
        verify(exactly = 0) { casinoAdminService.resetJackpot(any()) }
    }

    @Test
    fun `resetJackpotPool drains via the admin service for authorised actors`() {
        every { authorizer.canModerate(actor, guildId) } returns true
        every { casinoAdminService.resetJackpot(guildId) } returns 42_000L

        val result = service.resetJackpotPool(actor, guildId)

        assertNull(result.error)
        assertEquals(42_000L, result.drained)
        assertEquals(0L, result.newPool)
    }

    // ---------------- refundJackpotFromUser ----------------

    @Test
    fun `refundJackpotFromUser refuses non-moderators`() {
        every { authorizer.canModerate(actor, guildId) } returns false
        every { jackpotService.getPool(guildId) } returns 500L

        val result = service.refundJackpotFromUser(actor, guildId, source, amount = 100L)

        assertEquals(CasinoAuditService.NOT_ALLOWED, result.error)
        assertEquals(500L, result.newPool)
        verify(exactly = 0) { casinoAdminService.refundToJackpot(any(), any(), any()) }
    }

    @Test
    fun `refundJackpotFromUser rejects non-positive amounts before touching the admin service`() {
        every { authorizer.canModerate(actor, guildId) } returns true
        every { jackpotService.getPool(guildId) } returns 250L

        val zero = service.refundJackpotFromUser(actor, guildId, source, amount = 0L)
        assertEquals("Amount must be positive.", zero.error)

        val negative = service.refundJackpotFromUser(actor, guildId, source, amount = -1L)
        assertEquals("Amount must be positive.", negative.error)
        verify(exactly = 0) { casinoAdminService.refundToJackpot(any(), any(), any()) }
    }

    @Test
    fun `refundJackpotFromUser maps Ok outcome through`() {
        every { authorizer.canModerate(actor, guildId) } returns true
        every { casinoAdminService.refundToJackpot(source, guildId, 100L) } returns
            CasinoAdminService.RefundOutcome.Ok(drained = 100L, newPool = 600L, newSourceBalance = 50L)

        val result = service.refundJackpotFromUser(actor, guildId, source, amount = 100L)

        assertNull(result.error)
        assertEquals(100L, result.drained)
        assertEquals(600L, result.newPool)
        assertEquals(50L, result.newSourceBalance)
    }

    @Test
    fun `refundJackpotFromUser maps Insufficient outcome through with detail`() {
        every { authorizer.canModerate(actor, guildId) } returns true
        every { jackpotService.getPool(guildId) } returns 300L
        every { casinoAdminService.refundToJackpot(source, guildId, 100L) } returns
            CasinoAdminService.RefundOutcome.Insufficient(have = 30L, needed = 100L)

        val result = service.refundJackpotFromUser(actor, guildId, source, amount = 100L)

        val msg = requireNotNull(result.error)
        assertTrue(msg.contains("only 30"))
        assertTrue(msg.contains("100"))
        assertEquals(300L, result.newPool)
        assertEquals(30L, result.newSourceBalance)
    }

    @Test
    fun `refundJackpotFromUser maps InvalidAmount outcome`() {
        every { authorizer.canModerate(actor, guildId) } returns true
        every { jackpotService.getPool(guildId) } returns 100L
        every { casinoAdminService.refundToJackpot(source, guildId, 100L) } returns
            CasinoAdminService.RefundOutcome.InvalidAmount(amount = 100L)

        val result = service.refundJackpotFromUser(actor, guildId, source, amount = 100L)

        assertEquals("Amount must be positive.", result.error)
        assertEquals(0L, result.drained)
    }

    // ---------------- forceDailyDraw ----------------

    @Test
    fun `forceDailyDraw refuses non-moderators with all-zero result`() {
        every { authorizer.canModerate(actor, guildId) } returns false

        val result = service.forceDailyDraw(actor, guildId)

        assertEquals(CasinoAuditService.NOT_ALLOWED, result.error)
        assertFalse(result.drewPrior)
        assertFalse(result.openedNew)
        verify(exactly = 0) { jackpotLotteryService.drawLottery(any()) }
        verify(exactly = 0) { jackpotLotteryService.drawMatchLottery(any()) }
    }
}
