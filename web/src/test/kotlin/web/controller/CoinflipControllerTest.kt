package web.controller

import database.economy.Coinflip
import database.service.CoinflipService
import database.service.CoinflipService.FlipOutcome
import database.service.JackpotService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.EconomyWebService

class CoinflipControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var coinflipService: CoinflipService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var jda: JDA
    private lateinit var user: OAuth2User
    private lateinit var controller: CoinflipController

    @BeforeEach
    fun setup() {
        coinflipService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = CoinflipController(coinflipService, economyWebService, userService, jackpotService, marketService, jda)
    }

    @Test
    fun `flip win returns 200 with win-shaped payload`() {
        every { coinflipService.flip(discordId, guildId, 100L, Coinflip.Side.HEADS) } returns FlipOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            landed = Coinflip.Side.HEADS,
            predicted = Coinflip.Side.HEADS,
            newBalance = 1_100L
        )

        val response = controller.flip(guildId, FlipRequest(side = "HEADS", stake = 100L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(100L, body.net)
        assertEquals(200L, body.payout)
        assertEquals(1_100L, body.newBalance)
        assertEquals("HEADS", body.landed)
        assertEquals("HEADS", body.predicted)
    }

    @Test
    fun `flip lose returns 200 with negative net and win=false`() {
        every { coinflipService.flip(discordId, guildId, 50L, Coinflip.Side.TAILS) } returns FlipOutcome.Lose(
            stake = 50L,
            landed = Coinflip.Side.HEADS,
            predicted = Coinflip.Side.TAILS,
            newBalance = 950L
        )

        val response = controller.flip(guildId, FlipRequest(side = "TAILS", stake = 50L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(false, body.win)
        assertEquals(-50L, body.net)
        assertEquals(950L, body.newBalance)
        assertEquals("HEADS", body.landed)
        assertEquals("TAILS", body.predicted)
    }

    @Test
    fun `flip is case-insensitive on the side parameter`() {
        every { coinflipService.flip(discordId, guildId, 10L, Coinflip.Side.TAILS) } returns FlipOutcome.Lose(
            stake = 10L,
            landed = Coinflip.Side.HEADS,
            predicted = Coinflip.Side.TAILS,
            newBalance = 990L
        )

        val response = controller.flip(guildId, FlipRequest(side = "tails", stake = 10L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        verify(exactly = 1) { coinflipService.flip(discordId, guildId, 10L, Coinflip.Side.TAILS) }
    }

    @Test
    fun `flip rejects unknown side with 400`() {
        val response = controller.flip(guildId, FlipRequest(side = "edge", stake = 10L), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        verify(exactly = 0) { coinflipService.flip(any(), any(), any(), any()) }
    }

    @Test
    fun `flip returns 400 on insufficient credits`() {
        every { coinflipService.flip(discordId, guildId, 100L, Coinflip.Side.HEADS) } returns
            FlipOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.flip(guildId, FlipRequest(side = "HEADS", stake = 100L), user)

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("100"))
        assertTrue(response.body?.error!!.contains("30"))
    }

    @Test
    fun `flip returns 400 on invalid stake`() {
        every { coinflipService.flip(discordId, guildId, 5L, Coinflip.Side.HEADS) } returns
            FlipOutcome.InvalidStake(min = 10L, max = 1_000L)

        val response = controller.flip(guildId, FlipRequest(side = "HEADS", stake = 5L), user)

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("10"))
        assertTrue(response.body?.error!!.contains("1000"))
    }

    @Test
    fun `flip rejects with 403 when user is not a member of the guild`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.flip(guildId, FlipRequest(side = "HEADS", stake = 100L), user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { coinflipService.flip(any(), any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every { coinflipService.flip(discordId, guildId, 100L, Coinflip.Side.HEADS) } returns FlipOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            landed = Coinflip.Side.HEADS,
            predicted = Coinflip.Side.HEADS,
            newBalance = 5_100L,
            jackpotPayout = 4_000L
        )

        val response = controller.flip(guildId, FlipRequest(side = "HEADS", stake = 100L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(4_000L, response.body!!.jackpotPayout)
    }

    @Test
    fun `non-jackpot win does not include jackpotPayout`() {
        every { coinflipService.flip(discordId, guildId, 100L, Coinflip.Side.HEADS) } returns FlipOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            landed = Coinflip.Side.HEADS,
            predicted = Coinflip.Side.HEADS,
            newBalance = 1_100L
        )

        val response = controller.flip(guildId, FlipRequest(side = "HEADS", stake = 100L), user)

        assertEquals(null, response.body!!.jackpotPayout)
    }
}
