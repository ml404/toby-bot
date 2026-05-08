package database.service

import database.dto.ConfigDto
import database.dto.UserDto
import database.economy.Coinflip
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

class CoinflipServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var botSuspicionService: CoinflipBotSuspicionService
    private lateinit var coinflip: Coinflip
    private lateinit var service: CoinflipService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        botSuspicionService = mockk(relaxed = true)
        coinflip = mockk(relaxed = true)
        // relaxed=true returns 0 for Int — keeps the bot-suspicion gate
        // dormant by default for tests that don't care about it.
        every { botSuspicionService.recordAndScore(any(), any(), any(), any(), any()) } returns 0
        service = CoinflipService(
            userService, jackpotService, tradeService, marketService, configService,
            botSuspicionService, coinflip, Random(0),
        )
    }

    private fun userWithBalance(balance: Long): UserDto {
        return UserDto(discordId, guildId).apply { socialCredit = balance }
    }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // Force a 2× win: HEADS predicted, HEADS landed.
        every { coinflip.flip(Coinflip.Side.HEADS, any(), any()) } returns Coinflip.Flip(
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
        every { coinflip.flip(Coinflip.Side.HEADS, any(), any()) } returns Coinflip.Flip(
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

    @Test
    fun `loss tributes 10 percent of the stake into the jackpot pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { coinflip.flip(any(), any(), any()) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        val lose = assertInstanceOf(CoinflipService.FlipOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    private fun edgeMaxConfigReturns(value: String?) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT.configValue,
                guildId.toString()
            )
        } returns value?.let { ConfigDto(name = "x", value = it, guildId = guildId.toString()) }
    }

    @Test
    fun `flip forwards click signals to bot suspicion service`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { coinflip.flip(any(), any(), any()) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )

        service.flip(
            discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS,
            clickX = 350, clickY = 220, mouseMoved = false,
        )

        verify(exactly = 1) {
            botSuspicionService.recordAndScore(discordId, guildId, 350, 220, false)
        }
    }

    @Test
    fun `streak biases the loseProbabilityBoost passed into the flip`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { botSuspicionService.recordAndScore(any(), any(), any(), any(), any()) } returns 4
        // 4 streak × 2.5pp = 10 % house edge, well under the 30 % default cap.
        val boost = slot<Double>()
        every { coinflip.flip(any(), any(), capture(boost)) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )

        service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertEquals(0.10, boost.captured, 1e-9)
    }

    @Test
    fun `house edge is capped by COINFLIP_BOT_EDGE_MAX_PCT (default 30 percent)`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // streak 50 × 2.5 = 125 % house edge in raw form, must clamp to 30 %.
        every { botSuspicionService.recordAndScore(any(), any(), any(), any(), any()) } returns 50
        val boost = slot<Double>()
        every { coinflip.flip(any(), any(), capture(boost)) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )

        service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertEquals(0.30, boost.captured, 1e-9, "default cap is 30 % house edge")
    }

    @Test
    fun `admin override of COINFLIP_BOT_EDGE_MAX_PCT lowers the cap`() {
        edgeMaxConfigReturns("10")
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { botSuspicionService.recordAndScore(any(), any(), any(), any(), any()) } returns 50
        val boost = slot<Double>()
        every { coinflip.flip(any(), any(), capture(boost)) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )

        service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertEquals(0.10, boost.captured, 1e-9)
    }

    @Test
    fun `setting COINFLIP_BOT_EDGE_MAX_PCT to zero disables the gate even at high streak`() {
        edgeMaxConfigReturns("0")
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { botSuspicionService.recordAndScore(any(), any(), any(), any(), any()) } returns 100
        val boost = slot<Double>()
        every { coinflip.flip(any(), any(), capture(boost)) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )

        service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertEquals(0.0, boost.captured, 1e-9, "boost must be zero — gate fully disabled")
    }

    @Test
    fun `admin cannot exceed the hard MAX_EDGE_MAX_PCT ceiling`() {
        // Admin tries to set a 90 % cap; the service clamps to 50 %.
        edgeMaxConfigReturns("90")
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { botSuspicionService.recordAndScore(any(), any(), any(), any(), any()) } returns 100
        val boost = slot<Double>()
        every { coinflip.flip(any(), any(), capture(boost)) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )

        service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertTrue(boost.captured <= 0.50 + 1e-9, "boost ${boost.captured} must respect hard ceiling 0.50")
    }
}
