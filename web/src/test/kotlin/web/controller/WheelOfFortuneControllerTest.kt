package web.controller

import database.economy.WheelOfFortune
import database.service.WheelOfFortuneService
import database.service.WheelOfFortuneService.SpinOutcome
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

class WheelOfFortuneControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var wheelService: WheelOfFortuneService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var stakeBounds: StakeBounds
    private lateinit var user: OAuth2User
    private lateinit var controller: WheelOfFortuneController

    @BeforeEach
    fun setup() {
        wheelService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        stakeBounds = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = WheelOfFortuneController(wheelService, economyWebService, pageContext, stakeBounds)
    }

    @Test
    fun `spin win returns 200 with win-shaped payload`() {
        every { wheelService.spin(discordId, guildId, 100L, 5L, any(), any(), any(), any()) } returns SpinOutcome.Win(
            stake = 100L, pickedMultiplier = 5L, landedMultiplier = 5L,
            payout = 500L, net = 400L, newBalance = 1_400L,
        )

        val response = controller.spin(guildId, WheelSpinRequest(stake = 100L, pick = 5L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(5L, body.pick)
        assertEquals(5L, body.landed)
        assertEquals(400L, body.net)
    }

    @Test
    fun `spin lose returns 200 with win=false and negative net`() {
        every { wheelService.spin(discordId, guildId, 100L, 5L, any(), any(), any(), any()) } returns SpinOutcome.Lose(
            stake = 100L, pickedMultiplier = 5L, landedMultiplier = 2L,
            newBalance = 400L, lossTribute = 10L,
        )

        val response = controller.spin(guildId, WheelSpinRequest(stake = 100L, pick = 5L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(false, body.win)
        assertEquals(-100L, body.net)
        assertEquals(2L, body.landed)
        assertEquals(10L, body.lossTribute)
    }

    @Test
    fun `invalid pick returns 400`() {
        every { wheelService.spin(discordId, guildId, 100L, 7L, any(), any(), any(), any()) } returns
            SpinOutcome.InvalidPick(picks = WheelOfFortune.PICKS)

        val response = controller.spin(guildId, WheelSpinRequest(stake = 100L, pick = 7L), user)

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("2"))
    }

    @Test
    fun `spin returns 400 on insufficient credits`() {
        every { wheelService.spin(discordId, guildId, 100L, 2L, any(), any(), any(), any()) } returns
            SpinOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.spin(guildId, WheelSpinRequest(stake = 100L, pick = 2L), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `spin returns 400 on invalid stake`() {
        every { wheelService.spin(discordId, guildId, 5L, 2L, any(), any(), any(), any()) } returns
            SpinOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.spin(guildId, WheelSpinRequest(stake = 5L, pick = 2L), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `spin rejects with 403 when user is not a member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.spin(guildId, WheelSpinRequest(stake = 100L, pick = 2L), user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { wheelService.spin(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `controller forwards bot-suspicion signals into the service`() {
        every {
            wheelService.spin(any(), any(), any(), any(), any(), any(), any(), any())
        } returns SpinOutcome.Lose(
            stake = 10L, pickedMultiplier = 2L, landedMultiplier = 5L, newBalance = 90L,
        )

        controller.spin(guildId, WheelSpinRequest(
            stake = 10L, pick = 2L,
            clickX = 350, clickY = 220, mouseMoved = false,
        ), user)

        verify(exactly = 1) {
            wheelService.spin(
                discordId, guildId, 10L, 2L, false,
                clickX = 350, clickY = 220, mouseMoved = false,
            )
        }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout`() {
        every { wheelService.spin(discordId, guildId, 100L, 10L, any(), any(), any(), any()) } returns SpinOutcome.Win(
            stake = 100L, pickedMultiplier = 10L, landedMultiplier = 10L,
            payout = 1_000L, net = 900L, newBalance = 10_900L, jackpotPayout = 10_000L,
        )

        val response = controller.spin(guildId, WheelSpinRequest(stake = 100L, pick = 10L), user)

        assertEquals(10_000L, response.body!!.jackpotPayout)
    }
}
