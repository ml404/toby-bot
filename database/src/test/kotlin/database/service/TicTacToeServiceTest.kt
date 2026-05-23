package database.service

import common.events.TicTacToeResolvedEvent
import database.dto.ConfigDto
import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Behavioural tests for [TicTacToeService]. Covers the three resolve
 * branches (clean win by completed line, forfeit/timeout walkover via
 * explicit winner, draw) plus the preflight + accept guardrails, with
 * both stake-bearing and free-play paths exercised.
 */
class TicTacToeServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var xpAwardService: XpAwardService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: TicTacToeService

    @BeforeEach
    fun setUp() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        xpAwardService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        service = TicTacToeService(userService, jackpotService, configService, xpAwardService, eventPublisher)

        // Default stake bounds: 0..500.
        every { configService.getConfigByName(ConfigDto.Configurations.TICTACTOE_MIN_STAKE.configValue, any()) } returns null
        every { configService.getConfigByName(ConfigDto.Configurations.TICTACTOE_MAX_STAKE.configValue, any()) } returns null
        // No tribute by default.
        every { configService.getConfigByName(ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue, any()) } returns
            ConfigDto(name = "JACKPOT_LOSS_TRIBUTE_PCT", value = "0", guildId = guildId.toString())
        every { jackpotService.addToPool(any(), any()) } returns 0L
        every { xpAwardService.award(any(), any(), any(), any(), any(), any(), any()) } returns 0L
    }

    // ---- startMatch preflight ----

    @Test
    fun `startMatch rejects out-of-range stake`() {
        every { configService.getConfigByName(ConfigDto.Configurations.TICTACTOE_MIN_STAKE.configValue, any()) } returns
            ConfigDto(name = "TICTACTOE_MIN_STAKE", value = "10", guildId = guildId.toString())
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 5L)
        assertTrue(outcome is TicTacToeService.StartOutcome.InvalidStake)
    }

    @Test
    fun `startMatch rejects self-challenge`() {
        val outcome = service.startMatch(initiatorId, initiatorId, guildId, stake = 0L)
        assertTrue(outcome is TicTacToeService.StartOutcome.InvalidOpponent)
    }

    @Test
    fun `startMatch rejects unknown initiator`() {
        every { userService.getUserById(initiatorId, guildId) } returns null
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 0L)
        assertEquals(TicTacToeService.StartOutcome.UnknownInitiator, outcome)
    }

    @Test
    fun `startMatch rejects insufficient initiator balance`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 5L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 100L)
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertTrue(outcome is TicTacToeService.StartOutcome.InitiatorInsufficient)
    }

    @Test
    fun `startMatch happy path returns Ok with initiator balance`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 200L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 200L)
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertEquals(TicTacToeService.StartOutcome.Ok(200L), outcome)
    }

    // ---- acceptMatch ----

    @Test
    fun `acceptMatch with zero stake skips the user-table update path`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 0L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 0L)
        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, stake = 0L)
        assertEquals(TicTacToeService.AcceptOutcome.Ok(0L, 0L), outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `acceptMatch debits both balances on stake-bearing match`() {
        val initiator = userDto(initiatorId, balance = 100L)
        val opponent = userDto(opponentId, balance = 200L)
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns initiator
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns opponent

        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertEquals(TicTacToeService.AcceptOutcome.Ok(50L, 150L), outcome)
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 50L }) }
        verify(exactly = 1) { userService.updateUser(match { it.discordId == opponentId && it.socialCredit == 150L }) }
    }

    @Test
    fun `acceptMatch refuses when balance fell below stake between start and accept`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 10L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 200L)
        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertTrue(outcome is TicTacToeService.AcceptOutcome.InitiatorInsufficient)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- resolveMatch: stake-bearing ----

    @Test
    fun `resolveMatch credits winner and grants XP on clean win`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)
        every { xpAwardService.award(initiatorId, guildId, 10L, "tictactoe:win", any(), any(), any()) } returns 10L

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )
        assertTrue(outcome is TicTacToeService.ResolveOutcome.Win, "expected Win, got $outcome")
        val win = outcome as TicTacToeService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(opponentId, win.loserDiscordId)
        assertEquals(150L, win.winnerNewBalance) // 50 (post-debit) + 100 (pot)
        assertEquals(10L, win.xpGranted)
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 150L }) }
        // Loser was already debited at accept time — no further write.
        verify(exactly = 0) { userService.updateUser(match { it.discordId == opponentId }) }
    }

    @Test
    fun `resolveMatch refunds both stakes on draw`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = null,
        )
        assertTrue(outcome is TicTacToeService.ResolveOutcome.Draw)
        val draw = outcome as TicTacToeService.ResolveOutcome.Draw
        assertEquals(100L, draw.initiatorNewBalance) // 50 + 50 refund
        assertEquals(200L, draw.opponentNewBalance) // 150 + 50 refund
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 100L }) }
        verify(exactly = 1) { userService.updateUser(match { it.discordId == opponentId && it.socialCredit == 200L }) }
        verify(exactly = 0) { xpAwardService.award(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resolveMatch credits the explicit winner on a forfeit walkover`() {
        // Forfeit / timeout collapses to the same wager arithmetic as a
        // clean win — the button passes the survivor's discord id and
        // the service pays them the pot.
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = opponentId, // initiator forfeited
        )
        assertTrue(outcome is TicTacToeService.ResolveOutcome.Win)
        val win = outcome as TicTacToeService.ResolveOutcome.Win
        assertEquals(opponentId, win.winnerDiscordId)
        assertEquals(initiatorId, win.loserDiscordId)
        assertEquals(250L, win.winnerNewBalance) // 150 (post-debit) + 100 (pot)
    }

    // ---- resolveMatch: free play ----

    @Test
    fun `resolveMatch with zero stake skips user updates and only grants XP`() {
        every { xpAwardService.award(initiatorId, guildId, 10L, "tictactoe:win", any(), any(), any()) } returns 10L
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = initiatorId,
        )
        assertTrue(outcome is TicTacToeService.ResolveOutcome.Win)
        val win = outcome as TicTacToeService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(0L, win.pot)
        assertEquals(10L, win.xpGranted)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `resolveMatch with zero stake and null winner returns a free-play draw without XP`() {
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = null,
        )
        assertTrue(outcome is TicTacToeService.ResolveOutcome.Draw)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { xpAwardService.award(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ---- achievement event publishing ----

    @Test
    fun `resolveMatch publishes TicTacToeResolvedEvent on a clean win`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<TicTacToeResolvedEvent> {
                    it.winnerDiscordId == initiatorId &&
                        it.loserDiscordId == opponentId &&
                        it.guildId == guildId &&
                        it.stake == 50L &&
                        it.pot == 100L
                }
            )
        }
    }

    @Test
    fun `resolveMatch publishes TicTacToeResolvedEvent on free-play wins too`() {
        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = initiatorId,
        )
        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<TicTacToeResolvedEvent> {
                    it.winnerDiscordId == initiatorId &&
                        it.stake == 0L &&
                        it.pot == 0L
                }
            )
        }
    }

    @Test
    fun `resolveMatch does NOT publish on a draw`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = null,
        )
        verify(exactly = 0) { eventPublisher.publishEvent(any<TicTacToeResolvedEvent>()) }
    }

    @Test
    fun `resolveMatch jackpot tribute is deducted from winner payout`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)
        every { configService.getConfigByName(ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue, any()) } returns
            ConfigDto(name = "JACKPOT_LOSS_TRIBUTE_PCT", value = "20", guildId = guildId.toString())
        every { jackpotService.addToPool(guildId, any()) } returns 10L

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )
        val win = outcome as TicTacToeService.ResolveOutcome.Win
        // Winner balance was 50 post-debit; gets pot (100) minus tribute (10) = 90 → 140.
        assertEquals(140L, win.winnerNewBalance)
        assertEquals(10L, win.lossTribute)
        assertEquals(100L, win.pot)
    }

    private fun userDto(discordId: Long, balance: Long): UserDto = UserDto(
        discordId = discordId,
        guildId = guildId,
        socialCredit = balance,
    ).also { every { userService.updateUser(it) } answers { firstArg() } }
}
