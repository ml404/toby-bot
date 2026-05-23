package database.service

import common.events.RpsResolvedEvent
import database.dto.ConfigDto
import database.dto.UserDto
import common.rps.RpsEngine
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Behavioural tests for [RpsService]. Cover the four resolve branches
 * (clean win, draw, one-side forfeit, double-no-pick) plus the
 * preflight + accept guardrails, with both stake-bearing and free-play
 * paths exercised.
 */
class RpsServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var xpAwardService: XpAwardService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: RpsService

    @BeforeEach
    fun setUp() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        xpAwardService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        service = RpsService(userService, jackpotService, configService, xpAwardService, eventPublisher)

        // Default stake bounds: 0..500 (the RPS defaults).
        every { configService.getConfigByName(ConfigDto.Configurations.RPS_MIN_STAKE.configValue, any()) } returns null
        every { configService.getConfigByName(ConfigDto.Configurations.RPS_MAX_STAKE.configValue, any()) } returns null
        // No tribute by default (test-pure jackpot accounting).
        // JackpotHelper.divertOnLoss reads JACKPOT_LOSS_TRIBUTE_PCT from
        // configService and calls jackpotService.addToPool — default to
        // "no tribute" so test-pure jackpot accounting just works.
        every { configService.getConfigByName(ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue, any()) } returns
            ConfigDto(name = "JACKPOT_LOSS_TRIBUTE_PCT", value = "0", guildId = guildId.toString())
        every { jackpotService.addToPool(any(), any()) } returns 0L
        // No-op XP grant by default.
        every { xpAwardService.award(any(), any(), any(), any(), any(), any(), any()) } returns 0L
    }

    // ---- startMatch preflight ----

    @Test
    fun `startMatch rejects out-of-range stake`() {
        every { configService.getConfigByName(ConfigDto.Configurations.RPS_MIN_STAKE.configValue, any()) } returns
            ConfigDto(name = "RPS_MIN_STAKE", value = "10", guildId = guildId.toString())
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 5L)
        assertTrue(outcome is RpsService.StartOutcome.InvalidStake)
    }

    @Test
    fun `startMatch rejects self-challenge`() {
        val outcome = service.startMatch(initiatorId, initiatorId, guildId, stake = 0L)
        assertTrue(outcome is RpsService.StartOutcome.InvalidOpponent)
    }

    @Test
    fun `startMatch rejects unknown initiator`() {
        every { userService.getUserById(initiatorId, guildId) } returns null
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 0L)
        assertEquals(RpsService.StartOutcome.UnknownInitiator, outcome)
    }

    @Test
    fun `startMatch rejects insufficient initiator balance`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 5L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 100L)
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertTrue(outcome is RpsService.StartOutcome.InitiatorInsufficient)
    }

    @Test
    fun `startMatch happy path returns Ok with initiator balance`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 200L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 200L)
        val outcome = service.startMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertEquals(RpsService.StartOutcome.Ok(200L), outcome)
    }

    // ---- acceptMatch ----

    @Test
    fun `acceptMatch with zero stake skips the user-table update path`() {
        every { userService.getUserById(initiatorId, guildId) } returns userDto(initiatorId, balance = 0L)
        every { userService.getUserById(opponentId, guildId) } returns userDto(opponentId, balance = 0L)
        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, stake = 0L)
        assertEquals(RpsService.AcceptOutcome.Ok(0L, 0L), outcome)
        // Stake-free path must not lock or write.
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `acceptMatch debits both balances on stake-bearing match`() {
        val initiator = userDto(initiatorId, balance = 100L)
        val opponent = userDto(opponentId, balance = 200L)
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns initiator
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns opponent

        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertEquals(RpsService.AcceptOutcome.Ok(50L, 150L), outcome)
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 50L }) }
        verify(exactly = 1) { userService.updateUser(match { it.discordId == opponentId && it.socialCredit == 150L }) }
    }

    @Test
    fun `acceptMatch refuses when balance fell below stake between start and accept`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 10L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 200L)
        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, stake = 50L)
        assertTrue(outcome is RpsService.AcceptOutcome.InitiatorInsufficient)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- resolveMatch: both picked ----

    @Test
    fun `resolveMatch credits winner and grants XP on clean win`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)
        every { xpAwardService.award(initiatorId, guildId, 10L, "rps:win", any(), any(), any()) } returns 10L

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )
        // Initiator (winner) gets pot = 100; their balance was 50 (post-accept-debit), so 50 + 100 = 150.
        assertTrue(outcome is RpsService.ResolveOutcome.Win, "expected Win, got $outcome")
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(opponentId, win.loserDiscordId)
        assertEquals(150L, win.winnerNewBalance)
        assertEquals(10L, win.xpGranted)
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 150L }) }
        // Loser was already debited at accept time — NO further update.
        verify(exactly = 0) { userService.updateUser(match { it.discordId == opponentId }) }
    }

    @Test
    fun `resolveMatch refunds both stakes on draw`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.PAPER,
            opponentChoice = RpsEngine.Choice.PAPER,
        )
        assertTrue(outcome is RpsService.ResolveOutcome.Draw, "expected Draw, got $outcome")
        val draw = outcome as RpsService.ResolveOutcome.Draw
        assertEquals(100L, draw.initiatorNewBalance) // 50 + 50 refund
        assertEquals(200L, draw.opponentNewBalance) // 150 + 50 refund
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 100L }) }
        verify(exactly = 1) { userService.updateUser(match { it.discordId == opponentId && it.socialCredit == 200L }) }
        // No XP grant on draw.
        verify(exactly = 0) { xpAwardService.award(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resolveMatch credits picker when opponent never picked (forfeit)`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = null,
        )
        assertTrue(outcome is RpsService.ResolveOutcome.Win)
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(150L, win.winnerNewBalance)
    }

    @Test
    fun `resolveMatch double-refunds when neither side picked`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = null,
            opponentChoice = null,
        )
        assertTrue(outcome is RpsService.ResolveOutcome.DoubleRefund)
        val refund = outcome as RpsService.ResolveOutcome.DoubleRefund
        assertEquals(100L, refund.initiatorNewBalance)
        assertEquals(200L, refund.opponentNewBalance)
        verify(exactly = 1) { userService.updateUser(match { it.discordId == initiatorId && it.socialCredit == 100L }) }
        verify(exactly = 1) { userService.updateUser(match { it.discordId == opponentId && it.socialCredit == 200L }) }
    }

    // ---- resolveMatch: free play ----

    @Test
    fun `resolveMatch with zero stake skips user updates and only grants XP`() {
        every { xpAwardService.award(initiatorId, guildId, 10L, "rps:win", any(), any(), any()) } returns 10L
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )
        assertTrue(outcome is RpsService.ResolveOutcome.Win)
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(0L, win.pot)
        assertEquals(10L, win.xpGranted)
        // No user-table writes on free play.
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- achievement event publishing ----

    @Test
    fun `resolveMatch publishes RpsResolvedEvent on a clean win`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<RpsResolvedEvent> {
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
    fun `resolveMatch publishes RpsResolvedEvent on free-play wins too`() {
        // Free-play wins must still fire the event so `first_rps_win`
        // can unlock for players who never bet a credit.
        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<RpsResolvedEvent> {
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
            initiatorChoice = RpsEngine.Choice.PAPER,
            opponentChoice = RpsEngine.Choice.PAPER,
        )

        verify(exactly = 0) { eventPublisher.publishEvent(any<RpsResolvedEvent>()) }
    }

    @Test
    fun `resolveMatch does NOT publish on double-no-pick`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = null,
            opponentChoice = null,
        )

        verify(exactly = 0) { eventPublisher.publishEvent(any<RpsResolvedEvent>()) }
    }

    @Test
    fun `resolveMatch jackpot tribute is deducted from winner payout`() {
        every { userService.getUserByIdForUpdate(initiatorId, guildId) } returns userDto(initiatorId, balance = 50L)
        every { userService.getUserByIdForUpdate(opponentId, guildId) } returns userDto(opponentId, balance = 150L)
        // 20% tribute on a 50-credit stake → 10 credits routed to jackpot.
        every { configService.getConfigByName(ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue, any()) } returns
            ConfigDto(name = "JACKPOT_LOSS_TRIBUTE_PCT", value = "20", guildId = guildId.toString())
        every { jackpotService.addToPool(guildId, any()) } returns 10L

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        // Winner balance was 50 post-debit; gets pot (100) minus tribute (10) = 90 → new balance 140.
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
