package database.service

import database.dto.UserDto
import database.economy.Highlow
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HighlowServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var highlow: Highlow
    private lateinit var service: HighlowService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        highlow = mockk(relaxed = true)
        service = HighlowService(userService, jackpotService, tradeService, marketService, highlow, Random(0))
    }

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 5, next = 10, direction = Highlow.Direction.HIGHER, multiplier = 2L
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        val win = assertInstanceOf(HighlowService.PlayOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(200L, win.payout)
        assertEquals(100L, win.net)
        assertEquals(1_100L, win.newBalance)
        assertEquals(5, win.anchor)
        assertEquals(10, win.next)
        assertEquals(1_100L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 7, next = 7, direction = Highlow.Direction.HIGHER, multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        val lose = assertInstanceOf(HighlowService.PlayOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(7, lose.anchor)
        assertEquals(7, lose.next)
    }

    @Test
    fun `insufficient credits is rejected`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        assertInstanceOf(HighlowService.PlayOutcome.InsufficientCredits::class.java, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `invalid stake is rejected before locking the user`() {
        val outcome = service.play(discordId, guildId, stake = Highlow.MIN_STAKE - 1, direction = Highlow.Direction.HIGHER)

        assertInstanceOf(HighlowService.PlayOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        assertEquals(HighlowService.PlayOutcome.UnknownUser, outcome)
    }

    @Test
    fun `play with anchor delegates to resolve, not the bundled play`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.resolve(anchor = 7, direction = Highlow.Direction.HIGHER, random = any()) } returns
            Highlow.Hand(anchor = 7, next = 12, direction = Highlow.Direction.HIGHER, multiplier = 2L)
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(
            discordId, guildId, stake = 100L,
            direction = Highlow.Direction.HIGHER,
            anchor = 7
        )

        val win = assertInstanceOf(HighlowService.PlayOutcome.Win::class.java, outcome)
        assertEquals(7, win.anchor)
        assertEquals(12, win.next)
        verify(exactly = 1) { highlow.resolve(7, Highlow.Direction.HIGHER, any()) }
        verify(exactly = 0) { highlow.play(any(), any()) }
    }

    @Test
    fun `dealAnchor delegates to the underlying logic and does not touch the user`() {
        every { highlow.dealAnchor(any()) } returns 4

        val anchor = service.dealAnchor()

        assertEquals(4, anchor)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }
}
