package web.controller

import database.economy.Plinko
import database.service.PlinkoService
import database.service.PlinkoService.DropOutcome
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

class PlinkoControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var plinkoService: PlinkoService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var stakeBounds: StakeBounds
    private lateinit var user: OAuth2User
    private lateinit var controller: PlinkoController

    @BeforeEach
    fun setup() {
        plinkoService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        stakeBounds = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = PlinkoController(plinkoService, economyWebService, pageContext, stakeBounds)
    }

    @Test
    fun `drop win returns 200 with win-shaped payload`() {
        every { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.MEDIUM, any(), any(), any(), any()) } returns DropOutcome.Win(
            stake = 100L, risk = Plinko.Risk.MEDIUM, bucket = 0, multiplier = 12.0,
            payout = 1_200L, net = 1_100L, newBalance = 2_100L,
        )

        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "MEDIUM"), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(false, body.push)
        assertEquals(1_100L, body.net)
        assertEquals(0, body.bucket)
        assertEquals("MEDIUM", body.risk)
    }

    @Test
    fun `drop lose returns 200 with win=false`() {
        every { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.MEDIUM, any(), any(), any(), any()) } returns DropOutcome.Lose(
            stake = 100L, risk = Plinko.Risk.MEDIUM, bucket = 4, multiplier = 0.0,
            payout = 0L, net = -100L, newBalance = 400L, lossTribute = 10L,
        )

        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "MEDIUM"), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(false, body.win)
        assertEquals(false, body.push)
        assertEquals(-100L, body.net)
        assertEquals(10L, body.lossTribute)
    }

    @Test
    fun `drop push returns 200 with push=true and net 0`() {
        every { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.LOW, any(), any(), any(), any()) } returns DropOutcome.Push(
            stake = 100L, risk = Plinko.Risk.LOW, bucket = 3, newBalance = 500L,
        )

        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "LOW"), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.push)
        assertEquals(false, body.win)
        assertEquals(0L, body.net)
        assertEquals(1.0, body.multiplier)
    }

    @Test
    fun `unknown risk returns 400`() {
        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "WAYTOOHIGH"), user)

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("LOW"))
        verify(exactly = 0) { plinkoService.drop(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `risk parsing is case-insensitive`() {
        every { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.HIGH, any(), any(), any(), any()) } returns DropOutcome.Lose(
            stake = 100L, risk = Plinko.Risk.HIGH, bucket = 4, multiplier = 0.0,
            payout = 0L, net = -100L, newBalance = 400L,
        )

        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "high"), user)

        assertEquals(200, response.statusCode.value())
        verify(exactly = 1) {
            plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.HIGH, any(), any(), any(), any())
        }
    }

    @Test
    fun `drop returns 400 on insufficient credits`() {
        every { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.LOW, any(), any(), any(), any()) } returns
            DropOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "LOW"), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `drop returns 400 on invalid stake`() {
        every { plinkoService.drop(discordId, guildId, 5L, Plinko.Risk.LOW, any(), any(), any(), any()) } returns
            DropOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.drop(guildId, DropRequest(stake = 5L, risk = "LOW"), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `drop rejects with 403 when user is not a member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "LOW"), user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { plinkoService.drop(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `controller forwards bot-suspicion signals into the service`() {
        every {
            plinkoService.drop(any(), any(), any(), any(), any(), any(), any(), any())
        } returns DropOutcome.Lose(
            stake = 10L, risk = Plinko.Risk.LOW, bucket = 4, multiplier = 0.0,
            payout = 0L, net = -10L, newBalance = 90L,
        )

        controller.drop(guildId, DropRequest(
            stake = 10L, risk = "LOW",
            clickX = 350, clickY = 220, mouseMoved = false,
        ), user)

        verify(exactly = 1) {
            plinkoService.drop(
                discordId, guildId, 10L, Plinko.Risk.LOW, false,
                clickX = 350, clickY = 220, mouseMoved = false,
            )
        }
    }

    @Test
    fun `missing bot-suspicion fields are forwarded as null (Discord-equivalent path)`() {
        every {
            plinkoService.drop(any(), any(), any(), any(), any(), any(), any(), any())
        } returns DropOutcome.Lose(
            stake = 10L, risk = Plinko.Risk.LOW, bucket = 4, multiplier = 0.0,
            payout = 0L, net = -10L, newBalance = 90L,
        )

        controller.drop(guildId, DropRequest(stake = 10L, risk = "LOW"), user)

        verify(exactly = 1) {
            plinkoService.drop(
                discordId, guildId, 10L, Plinko.Risk.LOW, false,
                clickX = null, clickY = null, mouseMoved = null,
            )
        }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout`() {
        every { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.MEDIUM, any(), any(), any(), any()) } returns DropOutcome.Win(
            stake = 100L, risk = Plinko.Risk.MEDIUM, bucket = 0, multiplier = 12.0,
            payout = 1_200L, net = 1_100L, newBalance = 11_100L, jackpotPayout = 10_000L,
        )

        val response = controller.drop(guildId, DropRequest(stake = 100L, risk = "MEDIUM"), user)

        assertEquals(10_000L, response.body!!.jackpotPayout)
    }
}
