package database.service

import database.dto.UserDto
import database.economy.Coinflip
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CoinflipServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var coinflip: Coinflip
    private lateinit var service: CoinflipService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        coinflip = mockk(relaxed = true)
        service = CoinflipService(userService, jackpotService, coinflip, Random(0))
    }

    private fun userWithBalance(balance: Long): UserDto {
        return UserDto(discordId, guildId).apply { socialCredit = balance }
    }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // Force a 2× win: HEADS predicted, HEADS landed.
        every { coinflip.flip(Coinflip.Side.HEADS, any()) } returns Coinflip.Flip(
            landed = Coinflip.Side.HEADS,
            predicted = Coinflip.Side.HEADS,
            multiplier = 2L
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        val win = assertInstanceOf(CoinflipService.FlipOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(200L, win.payout)
        assertEquals(100L, win.net, "net = payout (200) - stake (100)")
        assertEquals(1_100L, win.newBalance, "1000 + (200 - 100)")
        assertEquals(Coinflip.Side.HEADS, win.landed)
        assertEquals(Coinflip.Side.HEADS, win.predicted)
        assertEquals(1_100L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { coinflip.flip(Coinflip.Side.HEADS, any()) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS,
            predicted = Coinflip.Side.HEADS,
            multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        val lose = assertInstanceOf(CoinflipService.FlipOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(Coinflip.Side.TAILS, lose.landed)
        assertEquals(Coinflip.Side.HEADS, lose.predicted)
    }

    @Test
    fun `insufficient credits is rejected without mutating balance`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        val rej = assertInstanceOf(CoinflipService.FlipOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(100L, rej.stake)
        assertEquals(50L, rej.have)
        assertEquals(50L, user.socialCredit, "balance untouched on rejection")
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake below MIN_STAKE is rejected before locking the user`() {
        val outcome = service.flip(discordId, guildId, stake = Coinflip.MIN_STAKE - 1, predicted = Coinflip.Side.HEADS)

        assertInstanceOf(CoinflipService.FlipOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `stake above MAX_STAKE is rejected before locking the user`() {
        val outcome = service.flip(discordId, guildId, stake = Coinflip.MAX_STAKE + 1, predicted = Coinflip.Side.HEADS)

        assertInstanceOf(CoinflipService.FlipOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertEquals(CoinflipService.FlipOutcome.UnknownUser, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }
}
