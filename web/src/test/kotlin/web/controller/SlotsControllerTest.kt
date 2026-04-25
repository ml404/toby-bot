package web.controller

import database.economy.SlotMachine
import database.service.JackpotService
import database.service.SlotsService
import database.service.SlotsService.SpinOutcome
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

/**
 * The /spin endpoint is the public surface for the web `/casino/{guildId}/slots`
 * UI. Tests cover every SpinOutcome variant's HTTP shape — they're also the
 * regression guard against accidentally sharing the Discord side's outcome
 * mapping with the web's HTTP-status mapping (different concerns).
 */
class SlotsControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var slotsService: SlotsService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var jda: JDA
    private lateinit var user: OAuth2User
    private lateinit var controller: SlotsController

    @BeforeEach
    fun setup() {
        slotsService = mockk(relaxed = true)
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
        controller = SlotsController(slotsService, economyWebService, userService, jackpotService, marketService, jda)
    }

    @Test
    fun `spin win returns 200 with win-shaped payload`() {
        every { slotsService.spin(discordId, guildId, 100L) } returns SpinOutcome.Win(
            stake = 100L,
            multiplier = 5L,
            payout = 500L,
            net = 400L,
            symbols = listOf(
                SlotMachine.Symbol.CHERRY,
                SlotMachine.Symbol.CHERRY,
                SlotMachine.Symbol.CHERRY
            ),
            newBalance = 1_400L
        )

        val response = controller.spin(guildId, SpinRequest(stake = 100L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(5L, body.multiplier)
        assertEquals(500L, body.payout)
        assertEquals(400L, body.net)
        assertEquals(1_400L, body.newBalance)
        assertEquals(listOf("🍒", "🍒", "🍒"), body.symbols)
    }

    @Test
    fun `spin lose returns 200 with negative net and win=false`() {
        every { slotsService.spin(discordId, guildId, 50L) } returns SpinOutcome.Lose(
            stake = 50L,
            symbols = listOf(
                SlotMachine.Symbol.CHERRY,
                SlotMachine.Symbol.LEMON,
                SlotMachine.Symbol.STAR
            ),
            newBalance = 950L
        )

        val response = controller.spin(guildId, SpinRequest(stake = 50L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(false, body.win)
        assertEquals(-50L, body.net)
        assertEquals(950L, body.newBalance)
    }

    @Test
    fun `spin returns 400 with error message on insufficient credits`() {
        every { slotsService.spin(discordId, guildId, 100L) } returns
            SpinOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.spin(guildId, SpinRequest(stake = 100L), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(response.body?.error!!.contains("100"))
        assertTrue(response.body?.error!!.contains("30"))
    }

    @Test
    fun `spin returns 400 on invalid stake`() {
        every { slotsService.spin(discordId, guildId, 5L) } returns
            SpinOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.spin(guildId, SpinRequest(stake = 5L), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(response.body?.error!!.contains("10"))
        assertTrue(response.body?.error!!.contains("500"))
    }

    @Test
    fun `spin returns 400 on unknown user`() {
        every { slotsService.spin(discordId, guildId, 100L) } returns SpinOutcome.UnknownUser

        val response = controller.spin(guildId, SpinRequest(stake = 100L), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
    }

    @Test
    fun `spin rejects with 403 when user is not a member of the guild`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.spin(guildId, SpinRequest(stake = 100L), user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { slotsService.spin(any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every { slotsService.spin(discordId, guildId, 100L) } returns SpinOutcome.Win(
            stake = 100L,
            multiplier = 5L,
            payout = 500L,
            net = 400L,
            symbols = listOf(SlotMachine.Symbol.STAR, SlotMachine.Symbol.STAR, SlotMachine.Symbol.STAR),
            newBalance = 9_000L,
            jackpotPayout = 7_500L
        )

        val response = controller.spin(guildId, SpinRequest(stake = 100L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.win)
        assertEquals(7_500L, body.jackpotPayout, "jackpot payout passes straight through")
        assertEquals(9_000L, body.newBalance, "newBalance already includes the jackpot per service contract")
    }

    @Test
    fun `non-jackpot win does not include jackpotPayout`() {
        every { slotsService.spin(discordId, guildId, 100L) } returns SpinOutcome.Win(
            stake = 100L,
            multiplier = 2L,
            payout = 200L,
            net = 100L,
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.CHERRY),
            newBalance = 1_100L,
            jackpotPayout = 0L
        )

        val response = controller.spin(guildId, SpinRequest(stake = 100L), user)

        assertEquals(null, response.body!!.jackpotPayout, "zero jackpot is omitted from the JSON shape")
    }
}
