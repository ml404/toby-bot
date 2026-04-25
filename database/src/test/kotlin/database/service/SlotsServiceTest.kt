package database.service

import database.dto.UserDto
import database.economy.SlotMachine
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SlotsServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var machine: SlotMachine
    private lateinit var service: SlotsService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        machine = mockk(relaxed = true)
        service = SlotsService(userService, jackpotService, tradeService, marketService, machine, Random(0))
    }

    private fun userWithBalance(balance: Long): UserDto {
        return UserDto(discordId, guildId).apply { socialCredit = balance }
    }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // Force a 5x win on a 100 stake → +400 net.
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY),
            multiplier = 5L
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val win = assertInstanceOf(SlotsService.SpinOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(5L, win.multiplier)
        assertEquals(500L, win.payout)
        assertEquals(400L, win.net, "net = payout (500) - stake (100)")
        assertEquals(1_400L, win.newBalance, "1000 + (500 - 100)")
        assertEquals(1_400L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON, SlotMachine.Symbol.BELL),
            multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val lose = assertInstanceOf(SlotsService.SpinOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(400L, user.socialCredit)
    }

    @Test
    fun `insufficient credits is rejected without mutating balance`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val rej = assertInstanceOf(SlotsService.SpinOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(100L, rej.stake)
        assertEquals(50L, rej.have)
        assertEquals(50L, user.socialCredit, "balance untouched on rejection")
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake below MIN_STAKE is rejected before locking the user`() {
        val outcome = service.spin(discordId, guildId, stake = SlotMachine.MIN_STAKE - 1)

        assertInstanceOf(SlotsService.SpinOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake above MAX_STAKE is rejected before locking the user`() {
        val outcome = service.spin(discordId, guildId, stake = SlotMachine.MAX_STAKE + 1)

        assertInstanceOf(SlotsService.SpinOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.spin(discordId, guildId, stake = 100L)

        assertEquals(SlotsService.SpinOutcome.UnknownUser, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `null socialCredit is treated as zero (insufficient)`() {
        val user = UserDto(discordId, guildId).apply { socialCredit = null }
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val rej = assertInstanceOf(SlotsService.SpinOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(0L, rej.have)
    }
}
