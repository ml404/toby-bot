package database.service

import database.dto.ConfigDto
import database.dto.UserDto
import common.economy.Coinflip
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
import database.service.casino.CasinoEdgeService
import database.service.casino.coinflip.CoinflipService
import database.service.guild.ConfigService
import database.service.economy.EconomyTradeService
import database.service.economy.JackpotService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService

class CoinflipServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var casinoEdgeService: CasinoEdgeService
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
        casinoEdgeService = mockk(relaxed = true)
        coinflip = mockk(relaxed = true)
        // Default: pass the fair outcome through untouched. Bias-decision
        // tests use a more specific stub.
        every {
            casinoEdgeService.applyBotEdge<Coinflip.Flip>(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers { arg<Coinflip.Flip>(7) }
        service = CoinflipService(
            userService, jackpotService, tradeService, marketService, configService,
            casinoEdgeService, coinflip, Random(0),
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

    @Test
    fun `loss tributes 10 percent of the stake into the jackpot pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { coinflip.flip(any(), any()) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        val lose = assertInstanceOf(CoinflipService.FlipOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `flip forwards bot signals + coinflip gameKey + COINFLIP config to CasinoEdgeService`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val fair = Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L
        )
        every { coinflip.flip(Coinflip.Side.HEADS, any()) } returns fair

        service.flip(
            discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS,
            clickX = 350, clickY = 220, mouseMoved = false,
        )

        verify(exactly = 1) {
            casinoEdgeService.applyBotEdge(
                discordId = discordId,
                guildId = guildId,
                gameKey = "coinflip",
                clickX = 350, clickY = 220, mouseMoved = false,
                edgeMaxConfig = ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT,
                fairOutcome = fair,
                asLoss = any(),
            )
        }
    }

    @Test
    fun `forced-loss substitution from the edge service flips the landed side`() {
        // CasinoEdgeService decides to substitute the fair outcome — verify
        // the asLoss lambda we hand it produces a coinflip-shaped loss
        // (multiplier 0, landed = NOT predicted).
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val fairWin = Coinflip.Flip(
            landed = Coinflip.Side.HEADS, predicted = Coinflip.Side.HEADS, multiplier = 2L
        )
        every { coinflip.flip(Coinflip.Side.HEADS, any()) } returns fairWin
        // Capture the asLoss lambda and invoke it to inspect the substitute.
        val lossSlot = slot<() -> Coinflip.Flip>()
        every {
            casinoEdgeService.applyBotEdge<Coinflip.Flip>(
                any(), any(), any(), any(), any(), any(), any(), any(),
                asLoss = capture(lossSlot),
            )
        } answers { lossSlot.captured.invoke() }
        every { userService.updateUser(any()) } returns user

        val outcome = service.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        // The fair flip would have been a HEADS win; the substitution makes
        // it a TAILS landing, which reads as a loss to the wager pipeline.
        val lose = assertInstanceOf(CoinflipService.FlipOutcome.Lose::class.java, outcome)
        assertEquals(Coinflip.Side.TAILS, lose.landed, "substitute must land the OPPOSITE of predicted")
        assertEquals(Coinflip.Side.HEADS, lose.predicted)
    }

    // -------------------------------------------------------------------------
    // CoinflipWonEvent (PR #520 follow-up)
    // -------------------------------------------------------------------------

    private fun serviceWithPublisher(): Pair<CoinflipService, CasinoEventPublisherFake> {
        val publisher = CasinoEventPublisherFake()
        val withPublisher = CoinflipService(
            userService, jackpotService, tradeService, marketService, configService,
            casinoEdgeService, coinflip, Random(0), publisher,
        )
        return withPublisher to publisher
    }

    @Test
    fun `winning flip publishes exactly one CoinflipWonEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { coinflip.flip(Coinflip.Side.HEADS, any()) } returns Coinflip.Flip(
            landed = Coinflip.Side.HEADS, predicted = Coinflip.Side.HEADS, multiplier = 2L,
        )
        every { userService.updateUser(any()) } returns user

        svc.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertEquals(1, publisher.coinflipWins.size)
        val event = publisher.coinflipWins.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
    }

    @Test
    fun `losing flip publishes no CoinflipWonEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { coinflip.flip(Coinflip.Side.HEADS, any()) } returns Coinflip.Flip(
            landed = Coinflip.Side.TAILS, predicted = Coinflip.Side.HEADS, multiplier = 0L,
        )
        every { userService.updateUser(any()) } returns user

        svc.flip(discordId, guildId, stake = 100L, predicted = Coinflip.Side.HEADS)

        assertTrue(publisher.coinflipWins.isEmpty())
    }
}
