package database.service

import database.dto.UserDto
import database.economy.Plinko
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PlinkoServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var plinko: Plinko
    private lateinit var service: PlinkoService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        plinko = mockk(relaxed = true)
        service = PlinkoService(
            userService, jackpotService, tradeService, marketService, configService,
            plinko, Random(0)
        )
    }

    private fun userWithBalance(balance: Long): UserDto {
        return UserDto(discordId, guildId).apply { socialCredit = balance }
    }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { plinko.drop(Plinko.Risk.MEDIUM, any()) } returns Plinko.Drop(
            bucket = 0, multiplier = 12.0, risk = Plinko.Risk.MEDIUM,
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.drop(discordId, guildId, stake = 100L, risk = Plinko.Risk.MEDIUM)

        val win = assertInstanceOf(PlinkoService.DropOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(12.0, win.multiplier, 1e-9)
        assertEquals(1_200L, win.payout)
        assertEquals(1_100L, win.net, "net = payout (1200) - stake (100)")
        assertEquals(2_100L, win.newBalance, "1000 + (1200 - 100)")
        assertEquals(2_100L, captured.captured.socialCredit)
    }

    @Test
    fun `full bust calls divertOnLoss with the full stake`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { plinko.drop(Plinko.Risk.MEDIUM, any()) } returns Plinko.Drop(
            bucket = 4, multiplier = 0.0, risk = Plinko.Risk.MEDIUM,
        )
        every { userService.updateUser(any()) } returns user
        every { jackpotService.addToPool(guildId, any()) } returns 0L

        val outcome = service.drop(discordId, guildId, stake = 100L, risk = Plinko.Risk.MEDIUM)

        val lose = assertInstanceOf(PlinkoService.DropOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(0L, lose.payout)
        assertEquals(-100L, lose.net)
        assertEquals(400L, lose.newBalance)
        // 10% tribute applied to the full loss (= stake) → 10
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `partial loss tributes on the actual lost portion only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // LOW profile bucket 4: 0.4× — player keeps 40 from 100 stake,
        // losing 60. Tribute = 10% of 60 = 6, not 10.
        every { plinko.drop(Plinko.Risk.LOW, any()) } returns Plinko.Drop(
            bucket = 4, multiplier = 0.4, risk = Plinko.Risk.LOW,
        )
        every { userService.updateUser(any()) } returns user
        every { jackpotService.addToPool(guildId, any()) } returns 0L

        val outcome = service.drop(discordId, guildId, stake = 100L, risk = Plinko.Risk.LOW)

        val lose = assertInstanceOf(PlinkoService.DropOutcome.Lose::class.java, outcome)
        assertEquals(40L, lose.payout, "0.4 × 100 = 40 retained")
        assertEquals(-60L, lose.net, "net loss = -60")
        assertEquals(440L, lose.newBalance)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 6L) }
    }

    @Test
    fun `push neither tributes nor rolls jackpot`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { plinko.drop(Plinko.Risk.LOW, any()) } returns Plinko.Drop(
            bucket = 3, multiplier = 1.0, risk = Plinko.Risk.LOW,
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.drop(discordId, guildId, stake = 100L, risk = Plinko.Risk.LOW)

        val push = assertInstanceOf(PlinkoService.DropOutcome.Push::class.java, outcome)
        assertEquals(100L, push.stake)
        assertEquals(500L, push.newBalance, "stake returned in full — balance unchanged")
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
        verify(exactly = 0) { jackpotService.awardJackpot(any(), any()) }
    }

    @Test
    fun `insufficient credits is rejected without mutating balance`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.drop(discordId, guildId, stake = 100L, risk = Plinko.Risk.MEDIUM)

        val rej = assertInstanceOf(PlinkoService.DropOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(100L, rej.stake)
        assertEquals(50L, rej.have)
        assertEquals(50L, user.socialCredit, "balance untouched on rejection")
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake below MIN_STAKE is rejected before locking the user`() {
        val outcome = service.drop(discordId, guildId, stake = Plinko.MIN_STAKE - 1, risk = Plinko.Risk.LOW)

        assertInstanceOf(PlinkoService.DropOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `stake above MAX_STAKE is rejected before locking the user`() {
        val outcome = service.drop(discordId, guildId, stake = Plinko.MAX_STAKE + 1, risk = Plinko.Risk.LOW)

        assertInstanceOf(PlinkoService.DropOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.drop(discordId, guildId, stake = 100L, risk = Plinko.Risk.LOW)

        assertEquals(PlinkoService.DropOutcome.UnknownUser, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `win bucket on HIGH profile pays the configured 40x multiplier`() {
        // Sanity: HIGH profile bucket 0 = 40×; a 100 stake nets +3900.
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { plinko.drop(Plinko.Risk.HIGH, any()) } returns Plinko.Drop(
            bucket = 0, multiplier = 40.0, risk = Plinko.Risk.HIGH,
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.drop(discordId, guildId, stake = 100L, risk = Plinko.Risk.HIGH)

        val win = assertInstanceOf(PlinkoService.DropOutcome.Win::class.java, outcome)
        assertEquals(4_000L, win.payout)
        assertEquals(3_900L, win.net)
        assertTrue(win.bucket == 0)
    }
}
