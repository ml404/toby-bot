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
        // Lightweight in-memory session — only get/set/remove are exercised.
        session = inMemorySession()

        every { economyWebService.isMember(discordId, guildId) } returns true
        val guild = mockk<Guild>(relaxed = true).also {
            every { it.name } returns "Test Guild"
        }
        every { jda.getGuildById(guildId) } returns guild

        controller = HighlowController(highlowService, economyWebService, userService, jackpotService, marketService, jda)
    }

    @Test
    fun `GET draws an anchor on first hit and stores it in session`() {
        every { highlowService.dealAnchor() } returns 7

        val view = controller.page(guildId, user, session, ConcurrentModel(), RedirectAttributesModelMap())

        assertEquals("highlow", view)
        assertEquals(7, session.getAttribute(anchorKey))
        verify(exactly = 1) { highlowService.dealAnchor() }
    }

    @Test
    fun `GET reuses the session anchor on subsequent hits without redrawing`() {
        session.setAttribute(anchorKey, 11)

        controller.page(guildId, user, session, ConcurrentModel(), RedirectAttributesModelMap())

        verify(exactly = 0) { highlowService.dealAnchor() }
        assertEquals(11, session.getAttribute(anchorKey))
    }

    @Test
    fun `POST without an active anchor returns 400`() {
        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER", stake = 50L),
            user,
            session
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
        assertTrue(response.body!!.error!!.contains("No active round"))
        verify(exactly = 0) { highlowService.play(any(), any(), any(), any(), any<Int>()) }
    }

    @Test
    fun `POST consumes the session anchor and seeds a new one for the next round`() {
        session.setAttribute(anchorKey, 5)
        every {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER, 5)
        } returns PlayOutcome.Win(
            stake = 50L, payout = 100L, net = 50L,
            anchor = 5, next = 12,
            direction = Highlow.Direction.HIGHER,
            newBalance = 1_050L
        )
        every { highlowService.dealAnchor() } returns 9

        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER", stake = 50L),
            user,
            session
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.win)
        assertEquals(5, body.anchor)
        assertEquals(12, body.next)
        assertEquals(9, body.nextAnchor, "next round's anchor must be in the response")
        assertEquals(9, session.getAttribute(anchorKey), "session must hold the new anchor")
    }

    @Test
    fun `POST with insufficient credits keeps the session anchor in place for retry`() {
        session.setAttribute(anchorKey, 6)
        every { highlowService.play(discordId, guildId, 9_999L, Highlow.Direction.HIGHER, 6) } returns
            PlayOutcome.InsufficientCredits(stake = 9_999L, have = 100L)

        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER", stake = 9_999L),
            user,
            session
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(6, session.getAttribute(anchorKey), "anchor stays so the user can correct stake and retry")
        assertNull(response.body!!.nextAnchor)
        verify(exactly = 0) { highlowService.dealAnchor() }
    }

    @Test
    fun `POST with invalid direction returns 400 without touching the service`() {
        session.setAttribute(anchorKey, 4)

        val response = controller.play(
            guildId,
            PlayRequest(direction = "SIDEWAYS", stake = 50L),
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
        every {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER, 5)
        } returns PlayOutcome.Win(
            stake = 50L, payout = 100L, net = 50L,
            anchor = 5, next = 12,
            direction = Highlow.Direction.HIGHER,
            newBalance = 5_050L,
            jackpotPayout = 4_000L
        )
        every { highlowService.dealAnchor() } returns 9

        val response = controller.play(
            guildId,
            PlayRequest(direction = "HIGHER", stake = 50L),
            user,
            session
        )

        assertEquals(4_000L, response.body!!.jackpotPayout)
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
