package database.service

import database.dto.UserDto
import database.economy.Dice
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DiceServiceTest {

    private lateinit var userService: UserService
    private lateinit var dice: Dice
    private lateinit var service: DiceService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        dice = mockk(relaxed = true) {
            every { isValidPrediction(any()) } answers { firstArg<Int>() in 1..6 }
            every { sidesCount } returns 6
        }
        service = DiceService(userService, dice, Random(0))
    }

    private fun userWithBalance(balance: Long): UserDto {
        return UserDto(discordId, guildId).apply { socialCredit = balance }
    }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { dice.roll(4, any()) } returns Dice.Roll(landed = 4, predicted = 4, multiplier = 5L)
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.roll(discordId, guildId, stake = 100L, predicted = 4)

        val win = assertInstanceOf(DiceService.RollOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(500L, win.payout)
        assertEquals(400L, win.net, "net = payout (500) - stake (100)")
        assertEquals(1_400L, win.newBalance)
        assertEquals(4, win.landed)
        assertEquals(4, win.predicted)
        assertEquals(1_400L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { dice.roll(3, any()) } returns Dice.Roll(landed = 5, predicted = 3, multiplier = 0L)
        every { userService.updateUser(any()) } returns user

        val outcome = service.roll(discordId, guildId, stake = 100L, predicted = 3)

        val lose = assertInstanceOf(DiceService.RollOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(5, lose.landed)
        assertEquals(3, lose.predicted)
    }

    @Test
    fun `insufficient credits is rejected without mutating balance`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.roll(discordId, guildId, stake = 100L, predicted = 3)

        val rej = assertInstanceOf(DiceService.RollOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(50L, rej.have)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake below MIN_STAKE is rejected before locking the user`() {
        val outcome = service.roll(discordId, guildId, stake = Dice.MIN_STAKE - 1, predicted = 3)

        assertInstanceOf(DiceService.RollOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `prediction outside 1 to 6 is rejected before locking the user`() {
        val outcome = service.roll(discordId, guildId, stake = 100L, predicted = 7)

        val rej = assertInstanceOf(DiceService.RollOutcome.InvalidPrediction::class.java, outcome)
        assertEquals(1, rej.min)
        assertEquals(6, rej.max)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.roll(discordId, guildId, stake = 100L, predicted = 3)

        assertEquals(DiceService.RollOutcome.UnknownUser, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }
}
