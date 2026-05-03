package web.controller

import database.economy.Keno
import database.service.KenoService
import database.service.KenoService.PlayOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.CasinoPageContext
import web.service.EconomyWebService

class KenoControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var kenoService: KenoService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var user: OAuth2User
    private lateinit var controller: KenoController

    @BeforeEach
    fun setup() {
        kenoService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = KenoController(kenoService, economyWebService, pageContext)
    }

    @Test
    fun `Win returns 200 with picks, draws, hits, and the win-shaped payload`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(1, 2, 3, 4, 5), false)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 80_000L,
            net = 79_900L,
            picks = listOf(1, 2, 3, 4, 5),
            draws = (1..20).toList(),
            hits = 5,
            multiplier = 800.0,
            newBalance = 80_900L
        )

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(1, 2, 3, 4, 5), stake = 100L), user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(5, body.hits)
        assertEquals(listOf(1, 2, 3, 4, 5), body.picks)
        assertEquals((1..20).toList(), body.draws)
        assertEquals(800.0, body.multiplier)
        assertEquals(79_900L, body.net)
        assertEquals(80_000L, body.payout)
        assertEquals(80_900L, body.newBalance)
    }

    @Test
    fun `Lose returns 200 with negative net, win=false, draws preserved`() {
        every {
            kenoService.play(discordId, guildId, 50L, listOf(40, 50, 60), false)
        } returns PlayOutcome.Lose(
            stake = 50L,
            picks = listOf(40, 50, 60),
            draws = (1..20).toList(),
            hits = 0,
            newBalance = 950L
        )

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(40, 50, 60), stake = 50L), user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(false, body.win)
        assertEquals(0, body.hits)
        assertEquals(-50L, body.net)
        assertEquals(0L, body.payout)
        assertEquals(950L, body.newBalance)
        assertEquals(0.0, body.multiplier)
    }

    @Test
    fun `play returns 400 on insufficient credits`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(5), false)
        } returns PlayOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5), stake = 100L), user
        )

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("100"))
        assertTrue(response.body?.error!!.contains("30"))
    }

    @Test
    fun `play returns 400 on invalid stake`() {
        every {
            kenoService.play(discordId, guildId, 5L, listOf(5), false)
        } returns PlayOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5), stake = 5L), user
        )

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("10"))
        assertTrue(response.body?.error!!.contains("500"))
    }

    @Test
    fun `play returns 400 on invalid picks`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(0, 99), false)
        } returns PlayOutcome.InvalidPicks(min = 1, max = 10, poolMax = 80)

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(0, 99), stake = 100L), user
        )

        assertEquals(400, response.statusCode.value())
        val msg = response.body?.error!!
        assertTrue(msg.contains("1"))
        assertTrue(msg.contains("10"))
        assertTrue(msg.contains("80"))
    }

    @Test
    fun `play rejects with 403 when user is not a member of the guild`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5), stake = 100L), user
        )

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { kenoService.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(5, 7), false)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 1_460L,
            net = 1_360L,
            picks = listOf(5, 7),
            draws = (1..20).toList(),
            hits = 2,
            multiplier = 14.6,
            newBalance = 6_360L,
            jackpotPayout = 4_000L
        )

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5, 7), stake = 100L), user
        )

        assertEquals(4_000L, response.body!!.jackpotPayout)
    }

    @Test
    fun `non-jackpot win does not include jackpotPayout`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(5, 7), false)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 1_460L,
            net = 1_360L,
            picks = listOf(5, 7),
            draws = (1..20).toList(),
            hits = 2,
            multiplier = 14.6,
            newBalance = 2_360L
        )

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5, 7), stake = 100L), user
        )

        assertNull(response.body!!.jackpotPayout)
    }

    @Test
    fun `lose with loss tribute surfaces lossTribute on the response`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(5), false)
        } returns PlayOutcome.Lose(
            stake = 100L,
            picks = listOf(5),
            draws = (1..20).toList(),
            hits = 0,
            newBalance = 900L,
            lossTribute = 10L
        )

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5), stake = 100L), user
        )

        assertEquals(10L, response.body!!.lossTribute)
    }

    @Test
    fun `autoTopUp flag is forwarded to the service`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(5), true)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 350L,
            net = 250L,
            picks = listOf(5),
            draws = (1..20).toList(),
            hits = 1,
            multiplier = 3.5,
            newBalance = 1_250L,
            soldTobyCoins = 25L,
            newPrice = 4.0,
        )

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5), stake = 100L, autoTopUp = true), user
        )

        val body = response.body!!
        assertEquals(true, body.win)
        assertEquals(25L, body.soldTobyCoins)
        assertEquals(4.0, body.newPrice)
        verify(exactly = 1) {
            kenoService.play(discordId, guildId, 100L, listOf(5), true)
        }
    }

    @Test
    fun `insufficient TOBY for top-up returns 400`() {
        every {
            kenoService.play(discordId, guildId, 100L, listOf(5), true)
        } returns PlayOutcome.InsufficientCoinsForTopUp(needed = 50L, have = 5L)

        val response = controller.play(
            guildId, KenoPlayRequest(picks = listOf(5), stake = 100L, autoTopUp = true), user
        )

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("50"))
        assertTrue(response.body?.error!!.contains("5"))
    }
}
