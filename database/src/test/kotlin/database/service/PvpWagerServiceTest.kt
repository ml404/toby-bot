package database.service

import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import database.service.guild.ConfigService
import database.service.economy.JackpotService
import database.service.pvp.PvpWagerService
import database.service.user.UserService
import database.service.leveling.XpAwardService

/**
 * Behavioural tests for [PvpWagerService] — the wager primitives shared
 * across `/rps`, `/tictactoe`, and (future) `/connect4`. Per-game
 * services delegate here; the resolve-branching tests live in
 * RpsServiceTest / TicTacToeServiceTest with PvpWagerService mocked.
 */
class PvpWagerServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var xpAwardService: XpAwardService
    private lateinit var service: PvpWagerService

    @BeforeEach
    fun setUp() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        xpAwardService = mockk(relaxed = true)
        service = PvpWagerService(userService, jackpotService, configService, xpAwardService)

        // No tribute by default; per-test override raises it.
        every { configService.getConfigByName(ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue, any()) } returns
            ConfigDto(name = "JACKPOT_LOSS_TRIBUTE_PCT", value = "0", guildId = guildId.toString())
        every { jackpotService.addToPool(any(), any()) } returns 0L
        every { xpAwardService.award(any(), any(), any(), any(), any(), any(), any()) } returns 0L
    }

    // ---- preflightStart ----

    @Test
    fun `preflightStart rejects stake below min`() {
        val outcome = service.preflightStart(initiatorId, opponentId, guildId, stake = 5L, minStake = 10L, maxStake = 100L)
        assertTrue(outcome is PvpWagerService.StartOutcome.InvalidStake)
    }

    @Test
    fun `preflightStart rejects stake above max`() {
        val outcome = service.preflightStart(initiatorId, opponentId, guildId, stake = 500L, minStake = 0L, maxStake = 100L)
        assertTrue(outcome is PvpWagerService.StartOutcome.InvalidStake)
    }

    @Test
    fun `preflightStart rejects self-challenge`() {
        val outcome = service.preflightStart(initiatorId, initiatorId, guildId, stake = 0L, minStake = 0L, maxStake = 500L)
        assertTrue(outcome is PvpWagerService.StartOutcome.InvalidOpponent)
        assertEquals(
            PvpWagerService.StartOutcome.InvalidOpponent.Reason.SELF,
            (outcome as PvpWagerService.StartOutcome.InvalidOpponent).reason,
        )
    }

    @Test
    fun `preflightStart rejects unknown initiator`() {
        every { userService.getUserById(initiatorId, guildId) } returns null
        val outcome = service.preflightStart(initiatorId, opponentId, guildId, stake = 0L, minStake = 0L, maxStake = 500L)
        assertEquals(PvpWagerService.StartOutcome.UnknownInitiator, outcome)
    }

    @Test
    fun `preflightStart rejects unknown opponent`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 100L)
        every { userService.getUserById(opponentId, guildId) } returns null
        val outcome = service.preflightStart(initiatorId, opponentId, guildId, stake = 50L, minStake = 0L, maxStake = 500L)
        assertEquals(PvpWagerService.StartOutcome.UnknownOpponent, outcome)
    }

    @Test
    fun `preflightStart rejects insufficient initiator balance`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 5L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 100L)
        val outcome = service.preflightStart(initiatorId, opponentId, guildId, stake = 50L, minStake = 0L, maxStake = 500L)
        assertTrue(outcome is PvpWagerService.StartOutcome.InitiatorInsufficient)
    }

    @Test
    fun `preflightStart rejects insufficient opponent balance`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 100L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 5L)
        val outcome = service.preflightStart(initiatorId, opponentId, guildId, stake = 50L, minStake = 0L, maxStake = 500L)
        assertTrue(outcome is PvpWagerService.StartOutcome.OpponentInsufficient)
    }

    @Test
    fun `preflightStart happy path returns Ok with initiator balance`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 200L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 200L)
        val outcome = service.preflightStart(initiatorId, opponentId, guildId, stake = 50L, minStake = 0L, maxStake = 500L)
        assertEquals(PvpWagerService.StartOutcome.Ok(200L), outcome)
    }

    // ---- debitBoth ----

    @Test
    fun `debitBoth with zero stake skips the user-table update path`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 0L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 0L)
        val outcome = service.debitBoth(initiatorId, opponentId, guildId, stake = 0L)
        assertEquals(PvpWagerService.AcceptOutcome.Ok(0L, 0L), outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `debitBoth debits both balances on stake-bearing match`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 100L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 200L)

        val outcome = service.debitBoth(initiatorId, opponentId, guildId, stake = 50L)
        assertEquals(PvpWagerService.AcceptOutcome.Ok(50L, 150L), outcome)
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 50L }) }
        verify(exactly = 1) { userService.updateUser(match { it.discordId == opponentId && it.socialCredit == 150L }) }
    }

    @Test
    fun `debitBoth refuses when balance fell below stake between start and accept`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 10L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 200L)
        val outcome = service.debitBoth(initiatorId, opponentId, guildId, stake = 50L)
        assertTrue(outcome is PvpWagerService.AcceptOutcome.InitiatorInsufficient)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `debitBoth surfaces UnknownInitiator when the row is missing under lock`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns null
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 100L)
        val outcome = service.debitBoth(initiatorId, opponentId, guildId, stake = 50L)
        assertEquals(PvpWagerService.AcceptOutcome.UnknownInitiator, outcome)
    }

    // ---- payWinner ----

    @Test
    fun `payWinner credits the winner the pot minus tribute and grants XP`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)
        every { xpAwardService.award(initiatorId, guildId, 10L, "rps:win", any(), any(), any()) } returns 10L

        val result = service.payWinner(
            winnerDiscordId = initiatorId, loserDiscordId = opponentId,
            stake = 50L, guildId = guildId, xpReason = "rps:win", xpAmount = 10L,
        )
        assertNotNull(result)
        assertEquals(150L, result!!.winnerNewBalance) // 50 (post-debit) + 100 (pot)
        assertEquals(150L, result.loserNewBalance) // loser balance unchanged from post-debit
        assertEquals(100L, result.pot)
        assertEquals(0L, result.lossTribute)
        assertEquals(10L, result.xpGranted)
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 150L }) }
        // Loser was already debited at accept time — no further update.
        verify(exactly = 0) { userService.updateUser(match { it.discordId == opponentId }) }
    }

    @Test
    fun `payWinner deducts jackpot tribute from the winner payout`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)
        every { configService.getConfigByName(ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue, any()) } returns
            ConfigDto(name = "JACKPOT_LOSS_TRIBUTE_PCT", value = "20", guildId = guildId.toString())
        every { jackpotService.addToPool(guildId, any()) } returns 10L

        val result = service.payWinner(
            winnerDiscordId = initiatorId, loserDiscordId = opponentId,
            stake = 50L, guildId = guildId, xpReason = "rps:win", xpAmount = 10L,
        )
        assertNotNull(result)
        assertEquals(140L, result!!.winnerNewBalance) // 50 + (100 pot - 10 tribute) = 140
        assertEquals(10L, result.lossTribute)
        assertEquals(100L, result.pot)
    }

    @Test
    fun `payWinner with zero stake only grants XP and returns zero wager numbers`() {
        every { xpAwardService.award(initiatorId, guildId, 10L, "rps:win", any(), any(), any()) } returns 10L
        val result = service.payWinner(
            winnerDiscordId = initiatorId, loserDiscordId = opponentId,
            stake = 0L, guildId = guildId, xpReason = "rps:win", xpAmount = 10L,
        )
        assertNotNull(result)
        assertEquals(0L, result!!.pot)
        assertEquals(10L, result.xpGranted)
        verify(exactly = 0) { userService.updateUser(any()) }
        // No lock-and-fetch on the free-play path. lockUsersInAscendingOrder is a
        // top-level extension; verify the underlying getUserByIdForUpdate it calls.
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `payWinner returns null when the winner row is missing under lock`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns null
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)
        val result = service.payWinner(
            winnerDiscordId = initiatorId, loserDiscordId = opponentId,
            stake = 50L, guildId = guildId, xpReason = "rps:win", xpAmount = 10L,
        )
        assertNull(result)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- refundBoth ----

    @Test
    fun `refundBoth credits both players the stake back`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        val result = service.refundBoth(initiatorId, opponentId, stake = 50L, guildId = guildId)
        assertEquals(100L, result.initiatorNewBalance) // 50 + 50
        assertEquals(200L, result.opponentNewBalance) // 150 + 50
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 100L }) }
        verify(exactly = 1) { userService.updateUser(match { it.discordId == opponentId && it.socialCredit == 200L }) }
    }

    @Test
    fun `refundBoth with zero stake is a no-op`() {
        val result = service.refundBoth(initiatorId, opponentId, stake = 0L, guildId = guildId)
        assertEquals(0L, result.initiatorNewBalance)
        assertEquals(0L, result.opponentNewBalance)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    // ---- readStakeBounds ----

    @Test
    fun `readStakeBounds returns defaults when no config is set`() {
        every { configService.getConfigByName(any(), any()) } returns null
        val (min, max) = service.readStakeBounds(
            guildId, ConfigDto.Configurations.RPS_MIN_STAKE, ConfigDto.Configurations.RPS_MAX_STAKE,
            defaultMin = 0L, defaultMax = 500L,
        )
        assertEquals(0L, min)
        assertEquals(500L, max)
    }

    @Test
    fun `readStakeBounds reads min and max from the config service`() {
        every { configService.getConfigByName(ConfigDto.Configurations.RPS_MIN_STAKE.configValue, any()) } returns
            ConfigDto(name = "RPS_MIN_STAKE", value = "10", guildId = guildId.toString())
        every { configService.getConfigByName(ConfigDto.Configurations.RPS_MAX_STAKE.configValue, any()) } returns
            ConfigDto(name = "RPS_MAX_STAKE", value = "200", guildId = guildId.toString())
        val (min, max) = service.readStakeBounds(
            guildId, ConfigDto.Configurations.RPS_MIN_STAKE, ConfigDto.Configurations.RPS_MAX_STAKE,
            defaultMin = 0L, defaultMax = 500L,
        )
        assertEquals(10L, min)
        assertEquals(200L, max)
    }

    private fun userDto(discordId: Long, balance: Long): UserDto = UserDto(
        discordId = discordId,
        guildId = guildId,
        socialCredit = balance,
    ).also { every { userService.updateUser(it) } answers { firstArg() } }
}
