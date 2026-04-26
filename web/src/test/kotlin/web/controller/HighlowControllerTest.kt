package web.controller

import database.economy.Highlow
import database.service.HighlowService
import database.service.HighlowService.PlayOutcome
import database.service.JackpotService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpSession
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.ConcurrentModel
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import web.service.EconomyWebService

class HighlowControllerTest {

    private val guildId = 42L
    private val discordId = 100L
    private val anchorKey = "highlow.anchor.$guildId"
    private val stakeKey = "highlow.stake.$guildId"
    private val autoTopUpKey = "highlow.autoTopUp.$guildId"

    private lateinit var highlowService: HighlowService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var jda: JDA
    private lateinit var user: OAuth2User
    private lateinit var session: HttpSession
    private lateinit var controller: HighlowController

    @BeforeEach
    fun setup() {
        highlowService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        session = inMemorySession()

        every { economyWebService.isMember(discordId, guildId) } returns true
        val guild = mockk<Guild>(relaxed = true).also {
            every { it.name } returns "Test Guild"
        }
        every { jda.getGuildById(guildId) } returns guild

        controller = HighlowController(highlowService, economyWebService, userService, jackpotService, marketService, jda)
    }

    @Test
    fun `GET does not draw an anchor`() {
        val view = controller.page(guildId, user, session, ConcurrentModel(), RedirectAttributesModelMap())

        assertEquals("highlow", view)
        assertNull(session.getAttribute(anchorKey))
        verify(exactly = 0) { highlowService.dealAnchor() }
    }

    @Test
    fun `GET surfaces an in-progress round so refreshing mid-round still works`() {
        session.setAttribute(anchorKey, 11)
        session.setAttribute(stakeKey, 75L)

        val model = ConcurrentModel()
        controller.page(guildId, user, session, model, RedirectAttributesModelMap())

        assertEquals(11, model.getAttribute("anchor"))
        assertEquals("J", model.getAttribute("anchorLabel"))
        assertEquals(75L, model.getAttribute("activeStake"))
        verify(exactly = 0) { highlowService.dealAnchor() }
    }

    @Test
    fun `start locks stake and deals anchor into session`() {
        every { highlowService.dealAnchor() } returns 9
        every { highlowService.payoutMultiplier(9, Highlow.Direction.HIGHER) } returns 3.0
        every { highlowService.payoutMultiplier(9, Highlow.Direction.LOWER) } returns 1.5

        val response = controller.start(
            guildId,
            StartRequest(stake = 50L, autoTopUp = false),
            user,
            session
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(true, response.body!!.ok)
        assertEquals(9, response.body!!.anchor)
        assertEquals(3.0, response.body!!.higherMultiplier)
        assertEquals(1.5, response.body!!.lowerMultiplier)
        assertEquals(9, session.getAttribute(anchorKey))
        assertEquals(50L, session.getAttribute(stakeKey))
        assertEquals(false, session.getAttribute(autoTopUpKey))
    }

    @Test
    fun `start records autoTopUp in session for the eventual play call`() {
        every { highlowService.dealAnchor() } returns 4

        controller.start(
            guildId,
            StartRequest(stake = 50L, autoTopUp = true),
            user,
            session
        )

        assertEquals(true, session.getAttribute(autoTopUpKey))
    }

    @Test
    fun `start rejects an out-of-range stake without dealing or persisting`() {
        val response = controller.start(
            guildId,
            StartRequest(stake = 9_999L),
            user,
            session
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
        assertNull(session.getAttribute(anchorKey))
        verify(exactly = 0) { highlowService.dealAnchor() }
    }

    @Test
    fun `play without an active round returns 400`() {
        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER"),
            user,
            session
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
        assertTrue(response.body!!.error!!.contains("No active round"))
        verify(exactly = 0) { highlowService.play(any(), any(), any(), any(), any<Int>()) }
    }

    @Test
    fun `play resolves with the session's stake and anchor and clears the round on a settled outcome`() {
        session.setAttribute(anchorKey, 5)
        session.setAttribute(stakeKey, 50L)
        session.setAttribute(autoTopUpKey, false)
        every {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER, 5, false)
        } returns PlayOutcome.Win(
            stake = 50L, payout = 100L, net = 50L,
            anchor = 5, next = 12,
            direction = Highlow.Direction.HIGHER,
            multiplier = 2.0,
            newBalance = 1_050L
        )

        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER"),
            user,
            session
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.win)
        assertEquals(5, body.anchor)
        assertEquals(12, body.next)
        assertEquals(2.0, body.multiplier)
        assertNull(session.getAttribute(anchorKey), "round must be cleared so the next bet starts fresh")
        assertNull(session.getAttribute(stakeKey))
    }

    @Test
    fun `play forwards the session autoTopUp flag to the service`() {
        session.setAttribute(anchorKey, 5)
        session.setAttribute(stakeKey, 50L)
        session.setAttribute(autoTopUpKey, true)
        every {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER, 5, true)
        } returns PlayOutcome.Lose(
            stake = 50L, anchor = 5, next = 5,
            direction = Highlow.Direction.HIGHER,
            newBalance = 950L
        )

        controller.play(guildId, PlayRequest(direction = "HIGHER"), user, session)

        verify(exactly = 1) {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER, 5, true)
        }
    }

    @Test
    fun `play with insufficient credits keeps the locked round in place for retry`() {
        session.setAttribute(anchorKey, 6)
        session.setAttribute(stakeKey, 9_999L)
        every {
            highlowService.play(discordId, guildId, 9_999L, Highlow.Direction.HIGHER, 6, false)
        } returns PlayOutcome.InsufficientCredits(stake = 9_999L, have = 100L)

        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER"),
            user,
            session
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(6, session.getAttribute(anchorKey), "anchor stays so the user can retry")
        assertEquals(9_999L, session.getAttribute(stakeKey), "stake also stays")
    }

    @Test
    fun `play with invalid direction returns 400 without touching the service`() {
        session.setAttribute(anchorKey, 4)
        session.setAttribute(stakeKey, 25L)

        val response = controller.play(
            guildId,
            PlayRequest(direction = "SIDEWAYS"),
            user,
            session
        )

        assertEquals(400, response.statusCode.value())
        assertNotNull(response.body!!.error)
        verify(exactly = 0) { highlowService.play(any(), any(), any(), any(), any<Int>()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        session.setAttribute(anchorKey, 5)
        session.setAttribute(stakeKey, 50L)
        every {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER, 5, false)
        } returns PlayOutcome.Win(
            stake = 50L, payout = 100L, net = 50L,
            anchor = 5, next = 12,
            direction = Highlow.Direction.HIGHER,
            multiplier = 2.0,
            newBalance = 5_050L,
            jackpotPayout = 4_000L
        )

        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER"),
            user,
            session
        )

        assertEquals(4_000L, response.body!!.jackpotPayout)
    }

    @Test
    fun `lose with loss tribute surfaces lossTribute on the response`() {
        session.setAttribute(anchorKey, 13)
        session.setAttribute(stakeKey, 50L)
        session.setAttribute(autoTopUpKey, false)
        every {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER, 13, false)
        } returns PlayOutcome.Lose(
            stake = 50L,
            anchor = 13,
            next = 7,
            direction = Highlow.Direction.HIGHER,
            newBalance = 950L,
            lossTribute = 5L
        )
        every { highlowService.dealAnchor() } returns 4

        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER"),
            user,
            session
        )

        assertEquals(5L, response.body!!.lossTribute)
    }

    private fun inMemorySession(): HttpSession {
        val store = HashMap<String, Any?>()
        return mockk(relaxed = true) {
            every { getAttribute(any()) } answers { store[firstArg()] }
            every { setAttribute(any(), any()) } answers {
                store[firstArg()] = secondArg()
            }
            every { removeAttribute(any()) } answers {
                store.remove(firstArg<String>())
            }
        }
    }
}
