package database.service

import database.dto.user.UserDto
import common.economy.Highlow
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random
import database.service.guild.ConfigService
import database.service.economy.EconomyTradeService
import database.service.casino.highlow.HighlowService
import database.service.economy.JackpotService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService

class HighlowServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
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
        configService = mockk(relaxed = true)
        highlow = mockk(relaxed = true)
        service = HighlowService(userService, jackpotService, tradeService, marketService, configService, highlow, Random(0))
    }

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 7, next = 10, direction = Highlow.Direction.HIGHER, multiplier = 2.0
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        val win = assertInstanceOf(HighlowService.PlayOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(200L, win.payout)
        assertEquals(100L, win.net)
        assertEquals(2.0, win.multiplier, 1e-9)
        assertEquals(1_100L, win.newBalance)
        assertEquals(7, win.anchor)
        assertEquals(10, win.next)
        assertEquals(1_100L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 7, next = 7, direction = Highlow.Direction.HIGHER, multiplier = 0.0
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
            Highlow.Hand(anchor = 7, next = 12, direction = Highlow.Direction.HIGHER, multiplier = 2.0)
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

    @Test
    fun `win never rolls the jackpot — HIGHLOW carries the global eligibility carve-out`() {
        // Even with a forced-hit RNG and a non-empty pool, a HighLow win
        // must surface jackpotPayout=0 and never touch the pool.
        // `JackpotGame.HIGHLOW.eligibleForJackpot = false` short-circuits
        // `JackpotHelper.rollOnWin` regardless of config.
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 7, next = 10, direction = Highlow.Direction.HIGHER, multiplier = 2.0
        )
        every { userService.updateUser(any()) } returns user
        every { jackpotService.awardJackpot(guildId, any()) } returns 9_999L  // would be banked if HIGHLOW were eligible

        val outcome = service.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        val win = assertInstanceOf(HighlowService.PlayOutcome.Win::class.java, outcome)
        assertEquals(0L, win.jackpotPayout)
        assertEquals(1_100L, win.newBalance, "newBalance must not include any jackpot top-up")
        verify(exactly = 0) { jackpotService.awardJackpot(any(), any()) }
    }

    @Test
    fun `loss tributes 10 percent of the stake into the jackpot pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 7, next = 7, direction = Highlow.Direction.HIGHER, multiplier = 0.0
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        val lose = assertInstanceOf(HighlowService.PlayOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    // -------------------------------------------------------------------------
    // HighlowHandResolvedEvent (PR #520 follow-up)
    // -------------------------------------------------------------------------

    private fun serviceWithPublisher(): Pair<HighlowService, CasinoEventPublisherFake> {
        val publisher = CasinoEventPublisherFake()
        val withPublisher = HighlowService(
            userService, jackpotService, tradeService, marketService, configService,
            highlow, Random(0), publisher,
        )
        return withPublisher to publisher
    }

    @Test
    fun `winning hand publishes HighlowHandResolvedEvent with isWin true`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 7, next = 10, direction = Highlow.Direction.HIGHER, multiplier = 2.0,
        )
        every { userService.updateUser(any()) } returns user

        svc.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER)

        assertEquals(1, publisher.highlowHandResolutions.size)
        val event = publisher.highlowHandResolutions.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
        assertEquals(true, event.isWin)
    }

    @Test
    fun `losing hand publishes HighlowHandResolvedEvent with isWin false`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.play(Highlow.Direction.LOWER, any()) } returns Highlow.Hand(
            anchor = 7, next = 10, direction = Highlow.Direction.LOWER, multiplier = 0.0,
        )
        every { userService.updateUser(any()) } returns user

        svc.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.LOWER)

        assertEquals(1, publisher.highlowHandResolutions.size)
        val event = publisher.highlowHandResolutions.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
        assertEquals(false, event.isWin)
    }

    @Test
    fun `stepwise (anchor-supplied) win publishes HighlowHandResolvedEvent the same way bundled flow does`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { highlow.resolve(7, Highlow.Direction.HIGHER, any()) } returns Highlow.Hand(
            anchor = 7, next = 10, direction = Highlow.Direction.HIGHER, multiplier = 2.0,
        )
        every { userService.updateUser(any()) } returns user

        svc.play(discordId, guildId, stake = 100L, direction = Highlow.Direction.HIGHER, anchor = 7)

        assertEquals(1, publisher.highlowHandResolutions.size)
        assertEquals(true, publisher.highlowHandResolutions.single().isWin)
    }
}
