package web.controller

import database.service.DiceService
import database.service.DiceService.RollOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.CasinoPageContext
import web.service.EconomyWebService

class DiceControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var diceService: DiceService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var user: OAuth2User
    private lateinit var controller: DiceController

    @BeforeEach
    fun setup() {
        diceService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = DiceController(diceService, economyWebService, pageContext)
    }

    @Test
    fun `roll win returns 200 with win-shaped payload`() {
        every { diceService.roll(discordId, guildId, 100L, 4) } returns RollOutcome.Win(
            stake = 100L, payout = 500L, net = 400L, landed = 4, predicted = 4, newBalance = 1_400L
        )

        val response = controller.roll(guildId, RollRequest(prediction = 4, stake = 100L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(400L, body.net)
        assertEquals(4, body.landed)
        assertEquals(4, body.predicted)
    }

    @Test
    fun `roll lose returns 200 with negative net and win=false`() {
        every { diceService.roll(discordId, guildId, 50L, 3) } returns RollOutcome.Lose(
            stake = 50L, landed = 1, predicted = 3, newBalance = 950L
        )

        val response = controller.roll(guildId, RollRequest(prediction = 3, stake = 50L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(false, body.win)
        assertEquals(-50L, body.net)
        assertEquals(1, body.landed)
    }

    @Test
    fun `roll returns 400 on invalid prediction`() {
        every { diceService.roll(discordId, guildId, 100L, 7) } returns
            RollOutcome.InvalidPrediction(min = 1, max = 6)

        val response = controller.roll(guildId, RollRequest(prediction = 7, stake = 100L), user)

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("1"))
        assertTrue(response.body?.error!!.contains("6"))
    }

    @Test
    fun `roll returns 400 on insufficient credits`() {
        every { diceService.roll(discordId, guildId, 100L, 3) } returns
            RollOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.roll(guildId, RollRequest(prediction = 3, stake = 100L), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `roll returns 400 on invalid stake`() {
        every { diceService.roll(discordId, guildId, 5L, 3) } returns
            RollOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.roll(guildId, RollRequest(prediction = 3, stake = 5L), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `roll rejects with 403 when user is not a member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.roll(guildId, RollRequest(prediction = 3, stake = 100L), user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { diceService.roll(any(), any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every { diceService.roll(discordId, guildId, 100L, 4) } returns RollOutcome.Win(
            stake = 100L, payout = 500L, net = 400L, landed = 4, predicted = 4,
            newBalance = 11_400L, jackpotPayout = 10_000L
        )

        val response = controller.roll(guildId, RollRequest(prediction = 4, stake = 100L), user)

        assertEquals(10_000L, response.body!!.jackpotPayout)
    }

    @Test
    fun `non-jackpot win does not include jackpotPayout`() {
        every { diceService.roll(discordId, guildId, 100L, 4) } returns RollOutcome.Win(
            stake = 100L, payout = 500L, net = 400L, landed = 4, predicted = 4, newBalance = 1_400L
        )

        val response = controller.roll(guildId, RollRequest(prediction = 4, stake = 100L), user)

        assertEquals(null, response.body!!.jackpotPayout)
    }

    @Test
    fun `lose with loss tribute surfaces lossTribute on the response`() {
        every { diceService.roll(discordId, guildId, 100L, 4) } returns RollOutcome.Lose(
            stake = 100L, landed = 1, predicted = 4, newBalance = 900L, lossTribute = 10L
        )

        val response = controller.roll(guildId, RollRequest(prediction = 4, stake = 100L), user)

        assertEquals(10L, response.body!!.lossTribute)
    }
}
