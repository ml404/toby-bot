package database.service

import database.dto.UserDto
import common.economy.Roulette
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random
import database.service.guild.ConfigService
import database.service.economy.EconomyTradeService
import database.service.economy.JackpotService
import database.service.casino.roulette.RouletteService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService

/**
 * Focused coverage of [RouletteService]'s achievement-event publication.
 * The broader spin/payout maths are covered by upstream `Roulette` engine
 * tests; this class pins the [common.events.RouletteStraightWinEvent]
 * wiring added in the PR #520 follow-up.
 */
class RouletteServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var roulette: Roulette

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        roulette = mockk(relaxed = true)
    }

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    private fun serviceWithPublisher(): Pair<RouletteService, CasinoEventPublisherFake> {
        val publisher = CasinoEventPublisherFake()
        val withPublisher = RouletteService(
            userService, jackpotService, tradeService, marketService, configService,
            roulette, Random(0), publisher,
        )
        return withPublisher to publisher
    }

    @Test
    fun `straight-up win publishes exactly one RouletteStraightWinEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { roulette.spin(Roulette.Bet.STRAIGHT, 17, any()) } returns Roulette.Spin(
            landed = 17, color = Roulette.Color.BLACK, bet = Roulette.Bet.STRAIGHT,
            straightNumber = 17, multiplier = 36L,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L, bet = Roulette.Bet.STRAIGHT, straightNumber = 17)

        assertEquals(1, publisher.rouletteStraightWins.size)
        val event = publisher.rouletteStraightWins.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
    }

    @Test
    fun `outside (RED) bet win publishes no RouletteStraightWinEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { roulette.spin(Roulette.Bet.RED, null, any()) } returns Roulette.Spin(
            landed = 7, color = Roulette.Color.RED, bet = Roulette.Bet.RED,
            straightNumber = null, multiplier = 2L,
        )
        every { userService.updateUser(any()) } returns user

        svc.spin(discordId, guildId, stake = 100L, bet = Roulette.Bet.RED)

        assertTrue(publisher.rouletteStraightWins.isEmpty())
    }

    @Test
    fun `straight-up loss publishes no RouletteStraightWinEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { roulette.spin(Roulette.Bet.STRAIGHT, 17, any()) } returns Roulette.Spin(
            landed = 23, color = Roulette.Color.RED, bet = Roulette.Bet.STRAIGHT,
            straightNumber = 17, multiplier = 0L,
        )
        every { userService.updateUser(any()) } returns user

        val outcome = svc.spin(discordId, guildId, stake = 100L, bet = Roulette.Bet.STRAIGHT, straightNumber = 17)

        assertInstanceOf(RouletteService.SpinOutcome.Lose::class.java, outcome)
        assertTrue(publisher.rouletteStraightWins.isEmpty())
    }
}
