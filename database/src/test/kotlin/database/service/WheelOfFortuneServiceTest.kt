package database.service

import database.dto.ConfigDto
import database.dto.UserDto
import database.economy.WheelOfFortune
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class WheelOfFortuneServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var casinoEdgeService: CasinoEdgeService
    private lateinit var wheel: WheelOfFortune
    private lateinit var service: WheelOfFortuneService

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
        wheel = mockk(relaxed = true)
        every { wheel.isValidPick(any()) } answers {
            firstArg<Long>() in WheelOfFortune.PICKS
        }
        every { wheel.picks() } returns WheelOfFortune.PICKS
        // Default: pass the fair spin through untouched.
        every {
            casinoEdgeService.applyBotEdge<WheelOfFortune.Spin>(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers { arg<WheelOfFortune.Spin>(7) }
        service = WheelOfFortuneService(
            userService, jackpotService, tradeService, marketService, configService,
            casinoEdgeService, wheel, Random(0)
        )
    }

    private fun userWithBalance(balance: Long): UserDto {
        return UserDto(discordId, guildId).apply { socialCredit = balance }
    }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { wheel.spin(5L, any()) } returns WheelOfFortune.Spin(
            landedMultiplier = 5L, pickedMultiplier = 5L
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L, pickedMultiplier = 5L)

        val win = assertInstanceOf(WheelOfFortuneService.SpinOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(5L, win.pickedMultiplier)
        assertEquals(5L, win.landedMultiplier)
        assertEquals(500L, win.payout)
        assertEquals(400L, win.net)
        assertEquals(1_400L, win.newBalance)
        assertEquals(1_400L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only and tributes 10 percent into pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { wheel.spin(5L, any()) } returns WheelOfFortune.Spin(
            landedMultiplier = 2L, pickedMultiplier = 5L
        )
        every { userService.updateUser(any()) } returns user
        every { jackpotService.addToPool(guildId, any()) } returns 0L

        val outcome = service.spin(discordId, guildId, stake = 100L, pickedMultiplier = 5L)

        val lose = assertInstanceOf(WheelOfFortuneService.SpinOutcome.Lose::class.java, outcome)
        assertEquals(5L, lose.pickedMultiplier)
        assertEquals(2L, lose.landedMultiplier)
        assertEquals(400L, lose.newBalance)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `invalid pick is rejected before locking the user`() {
        every { wheel.isValidPick(7L) } returns false
        every { wheel.picks() } returns WheelOfFortune.PICKS

        val outcome = service.spin(discordId, guildId, stake = 100L, pickedMultiplier = 7L)

        val rej = assertInstanceOf(WheelOfFortuneService.SpinOutcome.InvalidPick::class.java, outcome)
        assertEquals(WheelOfFortune.PICKS, rej.picks)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `insufficient credits is rejected without mutating balance`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L, pickedMultiplier = 2L)

        val rej = assertInstanceOf(WheelOfFortuneService.SpinOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(100L, rej.stake)
        assertEquals(50L, rej.have)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake below MIN_STAKE is rejected before locking the user`() {
        val outcome = service.spin(
            discordId, guildId, stake = WheelOfFortune.MIN_STAKE - 1, pickedMultiplier = 2L
        )

        assertInstanceOf(WheelOfFortuneService.SpinOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `stake above MAX_STAKE is rejected before locking the user`() {
        val outcome = service.spin(
            discordId, guildId, stake = WheelOfFortune.MAX_STAKE + 1, pickedMultiplier = 2L
        )

        assertInstanceOf(WheelOfFortuneService.SpinOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.spin(discordId, guildId, stake = 100L, pickedMultiplier = 2L)

        assertEquals(WheelOfFortuneService.SpinOutcome.UnknownUser, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `spin forwards bot signals + wheel gameKey + WHEEL_OF_FORTUNE config to CasinoEdgeService`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val fair = WheelOfFortune.Spin(landedMultiplier = 2L, pickedMultiplier = 5L)
        every { wheel.spin(5L, any()) } returns fair

        service.spin(
            discordId, guildId, stake = 100L, pickedMultiplier = 5L,
            clickX = 350, clickY = 220, mouseMoved = false,
        )

        verify(exactly = 1) {
            casinoEdgeService.applyBotEdge(
                discordId = discordId,
                guildId = guildId,
                gameKey = "wheel",
                clickX = 350, clickY = 220, mouseMoved = false,
                edgeMaxConfig = ConfigDto.Configurations.WHEEL_OF_FORTUNE_BOT_EDGE_MAX_PCT,
                fairOutcome = fair,
                asLoss = any(),
            )
        }
    }

    @Test
    fun `forced-loss substitution lands on a non-picked multiplier`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // Fair RNG would win — force a substituted loss instead.
        val fairWin = WheelOfFortune.Spin(landedMultiplier = 5L, pickedMultiplier = 5L)
        every { wheel.spin(5L, any()) } returns fairWin
        val lossSlot = slot<() -> WheelOfFortune.Spin>()
        every {
            casinoEdgeService.applyBotEdge<WheelOfFortune.Spin>(
                any(), any(), any(), any(), any(), any(), any(), any(),
                asLoss = capture(lossSlot),
            )
        } answers { lossSlot.captured.invoke() }
        every { userService.updateUser(any()) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L, pickedMultiplier = 5L)

        val lose = assertInstanceOf(WheelOfFortuneService.SpinOutcome.Lose::class.java, outcome)
        assertEquals(5L, lose.pickedMultiplier)
        assertNotEquals(5L, lose.landedMultiplier, "forced-loss must land on a non-picked multiplier")
    }

    @Test
    fun `pick 10x landing 10x pays 10x stake`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { wheel.spin(10L, any()) } returns WheelOfFortune.Spin(
            landedMultiplier = 10L, pickedMultiplier = 10L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L, pickedMultiplier = 10L)

        val win = assertInstanceOf(WheelOfFortuneService.SpinOutcome.Win::class.java, outcome)
        assertEquals(1_000L, win.payout)
        assertEquals(900L, win.net)
        assertEquals(1_900L, win.newBalance)
    }

    // -------------------------------------------------------------------------
    // WheelJackpotEvent (PR #520 follow-up)
    // -------------------------------------------------------------------------

    private fun serviceWithPublisher(): Pair<WheelOfFortuneService, CasinoEventPublisherFake> {
        val publisher = CasinoEventPublisherFake()
        val withPublisher = WheelOfFortuneService(
            userService, jackpotService, tradeService, marketService, configService,
            casinoEdgeService, wheel, Random(0), publisher,
        )
        return withPublisher to publisher
    }

    @Test
    fun `top-multiplier pick landing publishes exactly one WheelJackpotEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { wheel.spin(10L, any()) } returns WheelOfFortune.Spin(
            landedMultiplier = 10L, pickedMultiplier = 10L,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L, pickedMultiplier = 10L)

        assertEquals(1, publisher.wheelJackpots.size)
        val event = publisher.wheelJackpots.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
    }

    @Test
    fun `lower-multiplier pick landing publishes no WheelJackpotEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { wheel.spin(2L, any()) } returns WheelOfFortune.Spin(
            landedMultiplier = 2L, pickedMultiplier = 2L,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L, pickedMultiplier = 2L)

        assertTrue(publisher.wheelJackpots.isEmpty())
    }

    @Test
    fun `top-pick miss publishes no WheelJackpotEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // Picked 10×, landed on 3× → loss.
        every { wheel.spin(10L, any()) } returns WheelOfFortune.Spin(
            landedMultiplier = 3L, pickedMultiplier = 10L,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L, pickedMultiplier = 10L)

        assertTrue(publisher.wheelJackpots.isEmpty())
    }
}
