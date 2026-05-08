package web.controller

import database.economy.Roulette
import database.service.RouletteService
import database.service.RouletteService.SpinOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.CasinoPageContext
import web.casino.StakeBounds
import web.service.EconomyWebService

/**
 * The /spin endpoint is the public surface for the web `/casino/{guildId}/roulette`
 * UI. Tests cover every SpinOutcome variant's HTTP shape so a service-side
 * outcome change can't quietly break the JSON the page JS expects.
 */
class RouletteControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var rouletteService: RouletteService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var stakeBounds: StakeBounds
    private lateinit var user: OAuth2User
    private lateinit var controller: RouletteController

    @BeforeEach
    fun setup() {
        rouletteService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        stakeBounds = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = RouletteController(rouletteService, economyWebService, pageContext, stakeBounds)
    }

    @Test
    fun `spin win returns 200 with win-shaped payload`() {
        every { rouletteService.spin(discordId, guildId, 100L, Roulette.Bet.RED, null, false) } returns
            SpinOutcome.Win(
                stake = 100L,
                bet = Roulette.Bet.RED,
                landed = 7,
                color = Roulette.Color.RED,
                straightNumber = null,
                multiplier = 2L,
                payout = 200L,
                net = 100L,
                newBalance = 1_100L,
            )

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 100L, bet = "RED"),
            user,
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals("RED", body.bet)
        assertEquals(7, body.landed)
        assertEquals("RED", body.color)
        assertEquals(2L, body.multiplier)
        assertEquals(100L, body.net)
        assertEquals(1_100L, body.newBalance)
    }

    @Test
    fun `spin lose returns 200 with negative net and win=false`() {
        every { rouletteService.spin(discordId, guildId, 50L, Roulette.Bet.BLACK, null, false) } returns
            SpinOutcome.Lose(
                stake = 50L,
                bet = Roulette.Bet.BLACK,
                landed = 0,
                color = Roulette.Color.GREEN,
                straightNumber = null,
                newBalance = 950L,
            )

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 50L, bet = "BLACK"),
            user,
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(false, body.win)
        assertEquals(0, body.landed)
        assertEquals("GREEN", body.color)
        assertEquals(-50L, body.net)
        assertEquals(950L, body.newBalance)
    }

    @Test
    fun `spin straight win surfaces straightNumber on the response`() {
        every { rouletteService.spin(discordId, guildId, 10L, Roulette.Bet.STRAIGHT, 17, false) } returns
            SpinOutcome.Win(
                stake = 10L,
                bet = Roulette.Bet.STRAIGHT,
                landed = 17,
                color = Roulette.Color.BLACK,
                straightNumber = 17,
                multiplier = 36L,
                payout = 360L,
                net = 350L,
                newBalance = 1_350L,
            )

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 10L, bet = "STRAIGHT", number = 17),
            user,
        )

        assertEquals(17, response.body!!.straightNumber)
        assertEquals("STRAIGHT", response.body!!.bet)
        assertEquals(36L, response.body!!.multiplier)
    }

    @Test
    fun `spin returns 400 with error message on insufficient credits`() {
        every { rouletteService.spin(discordId, guildId, 100L, Roulette.Bet.RED, null, false) } returns
            SpinOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 100L, bet = "RED"),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(response.body?.error!!.contains("100"))
        assertTrue(response.body?.error!!.contains("30"))
    }

    @Test
    fun `spin returns 400 on invalid stake`() {
        every { rouletteService.spin(discordId, guildId, 5L, Roulette.Bet.RED, null, false) } returns
            SpinOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 5L, bet = "RED"),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(response.body?.error!!.contains("10"))
        assertTrue(response.body?.error!!.contains("500"))
    }

    @Test
    fun `spin returns 400 on invalid straight number`() {
        every { rouletteService.spin(discordId, guildId, 10L, Roulette.Bet.STRAIGHT, null, false) } returns
            SpinOutcome.InvalidNumber(min = 0, max = 36)

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 10L, bet = "STRAIGHT", number = null),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(response.body?.error!!.contains("0"))
        assertTrue(response.body?.error!!.contains("36"))
    }

    @Test
    fun `spin returns 400 with explanatory error when bet is unknown`() {
        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 50L, bet = "PURPLE"),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        verify(exactly = 0) { rouletteService.spin(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `spin returns 400 on unknown user`() {
        every { rouletteService.spin(discordId, guildId, 100L, Roulette.Bet.RED, null, false) } returns
            SpinOutcome.UnknownUser

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 100L, bet = "RED"),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
    }

    @Test
    fun `spin rejects with 403 when user is not a member of the guild`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 100L, bet = "RED"),
            user,
        )

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { rouletteService.spin(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every { rouletteService.spin(discordId, guildId, 100L, Roulette.Bet.RED, null, false) } returns
            SpinOutcome.Win(
                stake = 100L,
                bet = Roulette.Bet.RED,
                landed = 7,
                color = Roulette.Color.RED,
                straightNumber = null,
                multiplier = 2L,
                payout = 200L,
                net = 100L,
                newBalance = 9_100L,
                jackpotPayout = 8_000L,
            )

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 100L, bet = "RED"),
            user,
        )

        assertEquals(8_000L, response.body!!.jackpotPayout)
        assertEquals(9_100L, response.body!!.newBalance)
    }

    @Test
    fun `non-jackpot win does not include jackpotPayout`() {
        every { rouletteService.spin(discordId, guildId, 100L, Roulette.Bet.RED, null, false) } returns
            SpinOutcome.Win(
                stake = 100L,
                bet = Roulette.Bet.RED,
                landed = 7,
                color = Roulette.Color.RED,
                straightNumber = null,
                multiplier = 2L,
                payout = 200L,
                net = 100L,
                newBalance = 1_100L,
                jackpotPayout = 0L,
            )

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 100L, bet = "RED"),
            user,
        )

        assertEquals(null, response.body!!.jackpotPayout)
    }

    @Test
    fun `lose with loss tribute surfaces lossTribute on the response`() {
        every { rouletteService.spin(discordId, guildId, 100L, Roulette.Bet.RED, null, false) } returns
            SpinOutcome.Lose(
                stake = 100L,
                bet = Roulette.Bet.RED,
                landed = 0,
                color = Roulette.Color.GREEN,
                straightNumber = null,
                newBalance = 900L,
                lossTribute = 10L,
            )

        val response = controller.spin(
            guildId,
            RouletteSpinRequest(stake = 100L, bet = "RED"),
            user,
        )

        assertEquals(10L, response.body!!.lossTribute)
    }
}
