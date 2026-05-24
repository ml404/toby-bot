package database.service

import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import common.economy.SlotMachine
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
import database.service.guild.ConfigService
import database.service.economy.EconomyTradeService
import database.service.economy.JackpotService
import database.service.casino.slots.SlotsService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService

class SlotsServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var casinoEdgeService: CasinoEdgeService
    private lateinit var machine: SlotMachine
    private lateinit var service: SlotsService

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
        machine = mockk(relaxed = true)
        // Default: pass the fair Pull through untouched.
        every {
            casinoEdgeService.applyBotEdge<SlotMachine.Pull>(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers { arg<SlotMachine.Pull>(7) }
        service = SlotsService(
            userService, jackpotService, tradeService, marketService, configService,
            casinoEdgeService, machine, Random(0)
        )
    }

    private fun userWithBalance(balance: Long): UserDto {
        return UserDto(discordId, guildId).apply { socialCredit = balance }
    }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // Force a 5x win on a 100 stake → +400 net.
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY),
            multiplier = 5L
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val win = assertInstanceOf(SlotsService.SpinOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(5L, win.multiplier)
        assertEquals(500L, win.payout)
        assertEquals(400L, win.net, "net = payout (500) - stake (100)")
        assertEquals(1_400L, win.newBalance, "1000 + (500 - 100)")
        assertEquals(1_400L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON, SlotMachine.Symbol.BELL),
            multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val lose = assertInstanceOf(SlotsService.SpinOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(400L, user.socialCredit)
    }

    @Test
    fun `insufficient credits is rejected without mutating balance`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val rej = assertInstanceOf(SlotsService.SpinOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(100L, rej.stake)
        assertEquals(50L, rej.have)
        assertEquals(50L, user.socialCredit, "balance untouched on rejection")
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake below MIN_STAKE is rejected before locking the user`() {
        val outcome = service.spin(discordId, guildId, stake = SlotMachine.MIN_STAKE - 1)

        assertInstanceOf(SlotsService.SpinOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `stake above MAX_STAKE is rejected before locking the user`() {
        val outcome = service.spin(discordId, guildId, stake = SlotMachine.MAX_STAKE + 1)

        assertInstanceOf(SlotsService.SpinOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.spin(discordId, guildId, stake = 100L)

        assertEquals(SlotsService.SpinOutcome.UnknownUser, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `null socialCredit is treated as zero (insufficient)`() {
        val user = UserDto(discordId, guildId).apply { socialCredit = null }
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val rej = assertInstanceOf(SlotsService.SpinOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(0L, rej.have)
    }

    @Test
    fun `loss tributes 10 percent of the stake into the jackpot pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON, SlotMachine.Symbol.BELL),
            multiplier = 0L
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val lose = assertInstanceOf(SlotsService.SpinOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute, "10 % of 100 stake → 10 to jackpot")
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `spin forwards bot signals + slots gameKey + SLOTS config to CasinoEdgeService`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val fair = SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON, SlotMachine.Symbol.BELL),
            multiplier = 0L,
        )
        every { machine.pull(any()) } returns fair

        service.spin(
            discordId, guildId, stake = 100L,
            clickX = 350, clickY = 220, mouseMoved = false,
        )

        verify(exactly = 1) {
            casinoEdgeService.applyBotEdge(
                discordId = discordId,
                guildId = guildId,
                gameKey = "slots",
                clickX = 350, clickY = 220, mouseMoved = false,
                edgeMaxConfig = ConfigDto.Configurations.SLOTS_BOT_EDGE_MAX_PCT,
                fairOutcome = fair,
                asLoss = any(),
            )
        }
    }

    @Test
    fun `forced-loss substitution returns three distinct symbols and zero multiplier`() {
        // Verify the asLoss lambda invoked by the edge service produces a
        // Pull that SlotMachine.pull's win-detection (3-of-a-kind) treats
        // as a loss.
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val fairWin = SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.STAR, SlotMachine.Symbol.STAR, SlotMachine.Symbol.STAR),
            multiplier = 100L,
        )
        every { machine.pull(any()) } returns fairWin
        val lossSlot = slot<() -> SlotMachine.Pull>()
        every {
            casinoEdgeService.applyBotEdge<SlotMachine.Pull>(
                any(), any(), any(), any(), any(), any(), any(), any(),
                asLoss = capture(lossSlot),
            )
        } answers { lossSlot.captured.invoke() }
        every { userService.updateUser(any()) } returns user

        val outcome = service.spin(discordId, guildId, stake = 100L)

        val lose = assertInstanceOf(SlotsService.SpinOutcome.Lose::class.java, outcome)
        assertTrue(lose.symbols.toSet().size == 3, "three distinct symbols → guaranteed non-payout")
    }

    // -------------------------------------------------------------------------
    // SlotsJackpotEvent (PR #520 follow-up)
    // -------------------------------------------------------------------------

    private fun serviceWithPublisher(): Pair<SlotsService, CasinoEventPublisherFake> {
        val publisher = CasinoEventPublisherFake()
        val withPublisher = SlotsService(
            userService, jackpotService, tradeService, marketService, configService,
            casinoEdgeService, machine, Random(0), publisher,
        )
        return withPublisher to publisher
    }

    @Test
    fun `jackpot pull (100x STAR triple) publishes exactly one SlotsJackpotEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.STAR, SlotMachine.Symbol.STAR, SlotMachine.Symbol.STAR),
            multiplier = SlotMachine.JACKPOT_MULTIPLIER,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L)

        assertEquals(1, publisher.slotsJackpots.size)
        val event = publisher.slotsJackpots.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
    }

    @Test
    fun `non-jackpot win (cherries 5x) publishes no SlotsJackpotEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY),
            multiplier = 5L,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L)

        assertTrue(publisher.slotsJackpots.isEmpty())
    }

    @Test
    fun `losing pull publishes no SlotsJackpotEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { machine.pull(any()) } returns SlotMachine.Pull(
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON, SlotMachine.Symbol.BELL),
            multiplier = 0L,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L)

        assertTrue(publisher.slotsJackpots.isEmpty())
    }
}
