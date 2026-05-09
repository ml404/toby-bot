package database.service

import database.dto.ConfigDto
import database.dto.UserDto
import database.economy.Dice
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

class DiceServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var casinoEdgeService: CasinoEdgeService
    private lateinit var dice: Dice
    private lateinit var service: DiceService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        casinoEdgeService = mockk(relaxed = true)
        dice = mockk(relaxed = true) {
            every { isValidPrediction(any()) } answers { firstArg<Int>() in 1..6 }
            every { sidesCount } returns 6
        }
        // Default: pass the fair Roll through untouched.
        every {
            casinoEdgeService.applyBotEdge<Dice.Roll>(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers { arg<Dice.Roll>(7) }
        service = DiceService(
            userService, jackpotService, tradeService, marketService, configService,
            casinoEdgeService, dice, Random(0)
        )
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

    @Test
    fun `DICE_MAX_STAKE = 0 means no upper cap (unlimited)`() {
        // Per the moderation UI semantic: storing "0" in *_MAX_STAKE config
        // means "no upper cap" — cfgLongMax expands it to Long.MAX_VALUE so
        // the WagerHelper bounds check accepts arbitrary large stakes.
        // Without this test, a regression in cfgLongMax (or accidentally
        // switching the read back to cfgLong) would silently cap every
        // game at the floor and the "0 = unlimited" UI label would be a lie.
        val user = userWithBalance(10_000_000L)
        every {
            configService.getConfigByName(
                database.dto.ConfigDto.Configurations.DICE_MAX_STAKE.configValue,
                guildId.toString()
            )
        } returns database.dto.ConfigDto(name = "x", value = "0", guildId = guildId.toString())
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { dice.roll(4, any()) } returns Dice.Roll(landed = 4, predicted = 4, multiplier = 2L)
        every { userService.updateUser(any()) } returns user

        val outcome = service.roll(discordId, guildId, stake = 5_000_000L, predicted = 4)

        // Stake of 5,000,000 is way above the default MAX_STAKE (500) but
        // 0 = unlimited lifts the cap, so the roll completes as a Win.
        assertInstanceOf(DiceService.RollOutcome.Win::class.java, outcome)
    }

    @Test
    fun `loss tributes 10 percent of the stake into the jackpot pool`() {
        val user = UserDto(discordId, guildId).apply { socialCredit = 500L }
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { dice.roll(any(), any()) } returns Dice.Roll(landed = 1, predicted = 4, multiplier = 0L)
        every { userService.updateUser(any()) } returns user

        val outcome = service.roll(discordId, guildId, stake = 100L, predicted = 4)

        val lose = assertInstanceOf(DiceService.RollOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `roll forwards bot signals + dice gameKey + DICE config to CasinoEdgeService`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val fair = Dice.Roll(landed = 3, predicted = 4, multiplier = 0L)
        every { dice.roll(4, any()) } returns fair

        service.roll(
            discordId, guildId, stake = 100L, predicted = 4,
            clickX = 350, clickY = 220, mouseMoved = false,
        )

        verify(exactly = 1) {
            casinoEdgeService.applyBotEdge(
                discordId = discordId,
                guildId = guildId,
                gameKey = "dice",
                clickX = 350, clickY = 220, mouseMoved = false,
                edgeMaxConfig = ConfigDto.Configurations.DICE_BOT_EDGE_MAX_PCT,
                fairOutcome = fair,
                asLoss = any(),
            )
        }
    }

    @Test
    fun `forced-loss substitution lands on a non-predicted face`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val fairWin = Dice.Roll(landed = 4, predicted = 4, multiplier = 5L)
        every { dice.roll(4, any()) } returns fairWin
        val lossSlot = slot<() -> Dice.Roll>()
        every {
            casinoEdgeService.applyBotEdge<Dice.Roll>(
                any(), any(), any(), any(), any(), any(), any(), any(),
                asLoss = capture(lossSlot),
            )
        } answers { lossSlot.captured.invoke() }
        every { userService.updateUser(any()) } returns user

        val outcome = service.roll(discordId, guildId, stake = 100L, predicted = 4)

        val lose = assertInstanceOf(DiceService.RollOutcome.Lose::class.java, outcome)
        assertTrue(lose.landed != lose.predicted, "substitute must land on a non-predicted face")
        assertEquals(4, lose.predicted)
    }
}
