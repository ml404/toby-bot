package database.service

import database.dto.UserDto
import database.economy.ScratchCard
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

class ScratchServiceTest {

    private lateinit var userService: UserService
    private lateinit var card: ScratchCard
    private lateinit var service: ScratchService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        card = mockk(relaxed = true)
        service = ScratchService(userService, card, Random(0))
    }

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    @Test
    fun `win debits stake and credits payout`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { card.scratch(any()) } returns ScratchCard.Scratch(
            cells = List(5) { SlotMachine.Symbol.STAR },
            winningSymbol = SlotMachine.Symbol.STAR,
            matchCount = 5,
            multiplier = 90L
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.scratch(discordId, guildId, stake = 100L)

        val win = assertInstanceOf(ScratchService.ScratchOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(9_000L, win.payout)
        assertEquals(8_900L, win.net)
        assertEquals(9_900L, win.newBalance)
        assertEquals(SlotMachine.Symbol.STAR, win.winningSymbol)
        assertEquals(5, win.matchCount)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { card.scratch(any()) } returns ScratchCard.Scratch(
            cells = listOf(
                SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON,
                SlotMachine.Symbol.BELL, SlotMachine.Symbol.STAR, SlotMachine.Symbol.CHERRY
            ),
            winningSymbol = null,
            matchCount = 0,
            multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.scratch(discordId, guildId, stake = 100L)

        val lose = assertInstanceOf(ScratchService.ScratchOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(5, lose.cells.size)
    }

    @Test
    fun `insufficient credits is rejected`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.scratch(discordId, guildId, stake = 100L)

        assertInstanceOf(ScratchService.ScratchOutcome.InsufficientCredits::class.java, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `invalid stake is rejected`() {
        val outcome = service.scratch(discordId, guildId, stake = ScratchCard.MIN_STAKE - 1)
        assertInstanceOf(ScratchService.ScratchOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null
        val outcome = service.scratch(discordId, guildId, stake = 100L)
        assertEquals(ScratchService.ScratchOutcome.UnknownUser, outcome)
    }
}
