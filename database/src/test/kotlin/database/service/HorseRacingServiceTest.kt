package database.service

import database.dto.UserDto
import database.economy.HorseRacing
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HorseRacingServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var horseRacing: HorseRacing
    private lateinit var service: HorseRacingService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        horseRacing = mockk(relaxed = true)
        service = HorseRacingService(
            userService, jackpotService, tradeService, marketService, configService,
            horseRacing, Random(0),
        )
    }

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    @Test
    fun `win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { horseRacing.race(3, HorseRacing.Bet.WIN, any()) } returns HorseRacing.Race(
            finishingOrder = listOf(3, 1, 5, 2, 4, 6),
            bet = HorseRacing.Bet.WIN,
            pickedHorse = 3,
            multiplier = 5.3,
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.race(
            discordId, guildId, stake = 100L, pickedHorse = 3, bet = HorseRacing.Bet.WIN,
        )

        val win = assertInstanceOf(HorseRacingService.RaceOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(3, win.pickedHorse)
        assertEquals(HorseRacing.Bet.WIN, win.bet)
        assertEquals(listOf(3, 1, 5, 2, 4, 6), win.finishingOrder)
        assertEquals(5.3, win.multiplier, 1e-9)
        // payout = floor(5.3 * 100) = 530; net = 530 - 100 = 430; new balance = 1000 + 430 = 1430.
        assertEquals(530L, win.payout)
        assertEquals(430L, win.net)
        assertEquals(1_430L, win.newBalance)
        assertEquals(1_430L, captured.captured.socialCredit)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { horseRacing.race(2, HorseRacing.Bet.PLACE, any()) } returns HorseRacing.Race(
            finishingOrder = listOf(3, 1, 5, 2, 4, 6),
            bet = HorseRacing.Bet.PLACE,
            pickedHorse = 2,
            multiplier = 0.0,
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.race(
            discordId, guildId, stake = 100L, pickedHorse = 2, bet = HorseRacing.Bet.PLACE,
        )

        val lose = assertInstanceOf(HorseRacingService.RaceOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(2, lose.pickedHorse)
        assertEquals(HorseRacing.Bet.PLACE, lose.bet)
        assertEquals(listOf(3, 1, 5, 2, 4, 6), lose.finishingOrder)
    }

    @Test
    fun `invalid horse is rejected before locking the user`() {
        val outcome = service.race(
            discordId, guildId, stake = 100L, pickedHorse = 7, bet = HorseRacing.Bet.WIN,
        )

        val rejected = assertInstanceOf(HorseRacingService.RaceOutcome.InvalidHorse::class.java, outcome)
        assertEquals(1, rejected.min)
        assertEquals(HorseRacing.FIELD_SIZE, rejected.max)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `invalid stake is rejected before sampling the race`() {
        val outcome = service.race(
            discordId, guildId, stake = HorseRacing.MIN_STAKE - 1, pickedHorse = 1, bet = HorseRacing.Bet.WIN,
        )

        assertInstanceOf(HorseRacingService.RaceOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { horseRacing.race(any(), any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `insufficient credits is rejected without mutating the user`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.race(
            discordId, guildId, stake = 100L, pickedHorse = 1, bet = HorseRacing.Bet.WIN,
        )

        assertInstanceOf(HorseRacingService.RaceOutcome.InsufficientCredits::class.java, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { horseRacing.race(any(), any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.race(
            discordId, guildId, stake = 100L, pickedHorse = 1, bet = HorseRacing.Bet.WIN,
        )

        assertEquals(HorseRacingService.RaceOutcome.UnknownUser, outcome)
    }

    @Test
    fun `win does not roll the jackpot when the random draw misses`() {
        // JackpotHelper.rollOnWin compares `Random.nextDouble()` to the
        // base probability (1 % by default); with no config overrides
        // the relaxed `configService` mock returns `null` for every
        // lookup, leaving the default in place. The fixed-seed RNG in
        // the service is unlikely to hit on a single sample, so the win
        // path settles with jackpotPayout=0 and pure multiplier maths.
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { horseRacing.race(1, HorseRacing.Bet.WIN, any()) } returns HorseRacing.Race(
            finishingOrder = listOf(1, 2, 3, 4, 5, 6),
            bet = HorseRacing.Bet.WIN,
            pickedHorse = 1,
            multiplier = 3.2,
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.race(
            discordId, guildId, stake = 100L, pickedHorse = 1, bet = HorseRacing.Bet.WIN,
        )

        val win = assertInstanceOf(HorseRacingService.RaceOutcome.Win::class.java, outcome)
        // payout = floor(3.2 * 100) = 320; net = 220; balance = 1000 + 220 = 1220.
        assertEquals(320L, win.payout)
        assertEquals(220L, win.net)
        assertEquals(1_220L, win.newBalance)
    }

    @Test
    fun `loss tributes a fraction of the stake into the jackpot pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { horseRacing.race(6, HorseRacing.Bet.WIN, any()) } returns HorseRacing.Race(
            finishingOrder = listOf(1, 2, 3, 4, 5, 6),
            bet = HorseRacing.Bet.WIN,
            pickedHorse = 6,
            multiplier = 0.0,
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.race(
            discordId, guildId, stake = 100L, pickedHorse = 6, bet = HorseRacing.Bet.WIN,
        )

        val lose = assertInstanceOf(HorseRacingService.RaceOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }
}
