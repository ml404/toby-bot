package web.controller

import common.casino.horseracing.HorseRacing
import database.service.casino.horseracing.HorseRacingService
import database.service.casino.horseracing.HorseRacingService.RaceOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.CasinoPageContext
import web.casino.StakeBounds
import web.service.EconomyWebService

/**
 * The /race endpoint is the public surface for the web
 * `/casino/{guildId}/horse-racing` UI. Tests cover every RaceOutcome
 * variant's HTTP shape so a service-side outcome change can't quietly
 * break the JSON the page JS expects.
 */
class HorseRacingControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var service: HorseRacingService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var stakeBounds: StakeBounds
    private lateinit var user: OAuth2User
    private lateinit var controller: HorseRacingController

    @BeforeEach
    fun setup() {
        service = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        stakeBounds = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = HorseRacingController(service, economyWebService, pageContext, stakeBounds)
    }

    @Test
    fun `race win returns 200 with win-shaped payload`() {
        every { service.race(discordId, guildId, 100L, 3, HorseRacing.Bet.PLACE, false) } returns
            RaceOutcome.Win(
                stake = 100L,
                bet = HorseRacing.Bet.PLACE,
                pickedHorse = 3,
                finishingOrder = listOf(3, 1, 5, 2, 4, 6),
                multiplier = 2.6,
                payout = 260L,
                net = 160L,
                newBalance = 1_160L,
            )

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 100L, bet = "PLACE", horse = 3),
            user,
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals("PLACE", body.bet)
        assertEquals(3, body.pickedHorse)
        assertEquals(listOf(3, 1, 5, 2, 4, 6), body.finishingOrder)
        assertEquals(2.6, body.multiplier!!, 1e-9)
        assertEquals(160L, body.net)
        assertEquals(1_160L, body.newBalance)
    }

    @Test
    fun `race lose returns 200 with negative net and win=false`() {
        every { service.race(discordId, guildId, 50L, 6, HorseRacing.Bet.WIN, false) } returns
            RaceOutcome.Lose(
                stake = 50L,
                bet = HorseRacing.Bet.WIN,
                pickedHorse = 6,
                finishingOrder = listOf(1, 2, 3, 4, 5, 6),
                newBalance = 950L,
            )

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 50L, bet = "WIN", horse = 6),
            user,
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(false, body.win)
        assertEquals(6, body.pickedHorse)
        assertEquals(-50L, body.net)
        assertEquals(950L, body.newBalance)
    }

    @Test
    fun `race returns 400 with error message on insufficient credits`() {
        every { service.race(discordId, guildId, 100L, 1, HorseRacing.Bet.WIN, false) } returns
            RaceOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 100L, bet = "WIN", horse = 1),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertNotNull(response.body?.error)
        assertTrue(response.body!!.error!!.contains("100"))
        assertTrue(response.body!!.error!!.contains("30"))
    }

    @Test
    fun `race returns 400 on invalid stake`() {
        every { service.race(discordId, guildId, 5L, 1, HorseRacing.Bet.WIN, false) } returns
            RaceOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 5L, bet = "WIN", horse = 1),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(response.body!!.error!!.contains("10"))
        assertTrue(response.body!!.error!!.contains("500"))
    }

    @Test
    fun `race returns 400 on invalid horse`() {
        every { service.race(discordId, guildId, 100L, 9, HorseRacing.Bet.WIN, false) } returns
            RaceOutcome.InvalidHorse(min = 1, max = HorseRacing.FIELD_SIZE)

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 100L, bet = "WIN", horse = 9),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(response.body!!.error!!.contains("1"))
        assertTrue(response.body!!.error!!.contains(HorseRacing.FIELD_SIZE.toString()))
    }

    @Test
    fun `race returns 400 with explanatory error when bet is unknown`() {
        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 50L, bet = "EXACTA", horse = 1),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        verify(exactly = 0) { service.race(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `race returns 400 when horse is missing`() {
        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 50L, bet = "WIN", horse = null),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        verify(exactly = 0) { service.race(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `race returns 400 on unknown user`() {
        every { service.race(discordId, guildId, 100L, 1, HorseRacing.Bet.WIN, false) } returns
            RaceOutcome.UnknownUser

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 100L, bet = "WIN", horse = 1),
            user,
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
    }

    @Test
    fun `race rejects with 403 when user is not a member of the guild`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 100L, bet = "WIN", horse = 1),
            user,
        )

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { service.race(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every { service.race(discordId, guildId, 100L, 1, HorseRacing.Bet.WIN, false) } returns
            RaceOutcome.Win(
                stake = 100L,
                bet = HorseRacing.Bet.WIN,
                pickedHorse = 1,
                finishingOrder = listOf(1, 2, 3, 4, 5, 6),
                multiplier = 3.2,
                payout = 320L,
                net = 220L,
                newBalance = 9_220L,
                jackpotPayout = 8_000L,
            )

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 100L, bet = "WIN", horse = 1),
            user,
        )

        assertEquals(8_000L, response.body!!.jackpotPayout)
        assertEquals(9_220L, response.body!!.newBalance)
    }

    @Test
    fun `lose with loss tribute surfaces lossTribute on the response`() {
        every { service.race(discordId, guildId, 100L, 6, HorseRacing.Bet.WIN, false) } returns
            RaceOutcome.Lose(
                stake = 100L,
                bet = HorseRacing.Bet.WIN,
                pickedHorse = 6,
                finishingOrder = listOf(1, 2, 3, 4, 5, 6),
                newBalance = 900L,
                lossTribute = 10L,
            )

        val response = controller.race(
            guildId,
            HorseRacingRaceRequest(stake = 100L, bet = "WIN", horse = 6),
            user,
        )

        assertEquals(10L, response.body!!.lossTribute)
    }
}
