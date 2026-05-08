package database.service

import database.dto.UserDto
import database.economy.Keno
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class KenoServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var keno: Keno
    private lateinit var service: KenoService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        keno = mockk(relaxed = true)
        // Defaults so the engine doesn't NPE in tests that don't override:
        every { keno.drawNumbers(any()) } returns (1..20).toList()
        every { keno.play(any(), any()) } answers {
            val picks = firstArg<Set<Int>>()
            val draws = secondArg<List<Int>>()
            Keno.Hand(picks = picks.sorted(), draws = draws, hits = 0, multiplier = 0.0)
        }
        service = KenoService(
            userService, jackpotService, tradeService, marketService, configService,
            keno, Random(0)
        )
    }

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    @Test
    fun `Win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { keno.play(setOf(1, 2, 3, 4, 5), any()) } returns Keno.Hand(
            picks = listOf(1, 2, 3, 4, 5),
            draws = (1..20).toList(),
            hits = 5,
            multiplier = 800.0
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.play(
            discordId, guildId, stake = 10L, picks = listOf(1, 2, 3, 4, 5)
        )

        val win = assertInstanceOf(KenoService.PlayOutcome.Win::class.java, outcome)
        // 10 × 800 = 8000 payout, +7990 net.
        assertEquals(8_000L, win.payout)
        assertEquals(7_990L, win.net)
        assertEquals(800.0, win.multiplier, 1e-9)
        assertEquals(8_990L, win.newBalance)
        assertEquals(5, win.hits)
        assertEquals(listOf(1, 2, 3, 4, 5), win.picks)
        assertEquals(8_990L, captured.captured.socialCredit)
    }

    @Test
    fun `Lose debits stake only and leaves picks plus draws on the outcome`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { keno.play(setOf(40, 50, 60), any()) } returns Keno.Hand(
            picks = listOf(40, 50, 60),
            draws = (1..20).toList(),
            hits = 0,
            multiplier = 0.0
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(
            discordId, guildId, stake = 100L, picks = listOf(40, 50, 60)
        )

        val lose = assertInstanceOf(KenoService.PlayOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(0, lose.hits)
        assertEquals(listOf(40, 50, 60), lose.picks)
        assertEquals(20, lose.draws.size)
    }

    @Test
    fun `lose tributes 10 percent of the stake into the jackpot pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { keno.play(any(), any()) } returns Keno.Hand(
            picks = listOf(40), draws = (1..20).toList(), hits = 0, multiplier = 0.0
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, picks = listOf(40))

        val lose = assertInstanceOf(KenoService.PlayOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `insufficient credits is rejected without drawing`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, picks = listOf(5))

        assertInstanceOf(KenoService.PlayOutcome.InsufficientCredits::class.java, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { keno.drawNumbers(any()) }
        verify(exactly = 0) { keno.play(any(), any()) }
    }

    @Test
    fun `invalid stake is rejected before locking the user`() {
        val outcome = service.play(
            discordId, guildId, stake = Keno.MIN_STAKE - 1, picks = listOf(5)
        )

        assertInstanceOf(KenoService.PlayOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { keno.play(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.play(discordId, guildId, stake = 100L, picks = listOf(5))

        assertEquals(KenoService.PlayOutcome.UnknownUser, outcome)
    }

    @Test
    fun `picks below MIN_SPOTS rejected without touching the wallet`() {
        val outcome = service.play(discordId, guildId, stake = 100L, picks = emptyList())

        assertInstanceOf(KenoService.PlayOutcome.InvalidPicks::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `picks above MAX_SPOTS rejected without touching the wallet`() {
        val outcome = service.play(
            discordId, guildId, stake = 100L, picks = (1..(Keno.MAX_SPOTS + 1)).toList()
        )

        assertInstanceOf(KenoService.PlayOutcome.InvalidPicks::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `pick out of pool range rejected`() {
        val outcome = service.play(discordId, guildId, stake = 100L, picks = listOf(0, 5))

        assertInstanceOf(KenoService.PlayOutcome.InvalidPicks::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `duplicate picks rejected`() {
        val outcome = service.play(discordId, guildId, stake = 100L, picks = listOf(5, 7, 5))

        assertInstanceOf(KenoService.PlayOutcome.InvalidPicks::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `quickPick delegates to the engine and does not touch the user`() {
        every { keno.quickPick(5, any()) } returns listOf(2, 9, 17, 33, 71)

        val picks = service.quickPick(5)

        assertEquals(listOf(2, 9, 17, 33, 71), picks)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `maxMultiplier delegates to the engine`() {
        every { keno.maxMultiplier(7) } returns 7000.0

        assertEquals(7000.0, service.maxMultiplier(7), 1e-9)
    }
}
