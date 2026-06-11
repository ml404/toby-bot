package web.activity

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.ConcurrentModel
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import web.service.EconomyWebService

class ActivityControllerTest {

    private val sessions = mockk<ActivitySessions>()
    private val economyWebService = mockk<EconomyWebService>()
    private val jda = mockk<JDA>()
    private val controller = ActivityController(sessions, clientId = "  12345 ", economyWebService, jda)
    private val servletResponse = MockHttpServletResponse()

    private val user = mockk<OAuth2User> {
        every { getAttribute<String>("id") } returns "100"
        every { getAttribute<String>("username") } returns "tester"
    }

    @Test
    fun `shell renders the activity template with the trimmed client id`() {
        val model = ConcurrentModel()

        val view = controller.shell(model)

        assertEquals("activity", view)
        assertEquals("12345", model.getAttribute("clientId"))
    }

    @Test
    fun `casino picker renders for guild members with guild context and wallet`() {
        every { economyWebService.isMember(100L, 42L) } returns true
        every { economyWebService.getCredits(100L, 42L) } returns 1234L
        every { jda.getGuildById(42L) } returns mockk<Guild> { every { name } returns "Test Guild" }
        val model = ConcurrentModel()

        val view = controller.casino(42L, user, model, RedirectAttributesModelMap())

        assertEquals("activity-casino", view)
        assertEquals("42", model.getAttribute("guildId"))
        assertEquals("Test Guild", model.getAttribute("guildName"))
        assertEquals(1234L, model.getAttribute("credits"))
    }

    @Test
    fun `casino picker bounces non-members like every other per-guild page`() {
        every { economyWebService.isMember(100L, 42L) } returns false

        val view = controller.casino(42L, user, ConcurrentModel(), RedirectAttributesModelMap())

        assertEquals("redirect:/leaderboards", view)
    }

    @Test
    fun `token without a code is a 400`() {
        val response = controller.token(ActivityTokenRequest(code = "  "), servletResponse)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
    }

    @Test
    fun `failed exchange is a 502 with no tokens in the body and no cookie`() {
        every { sessions.exchange("bad-code") } returns null

        val response = controller.token(ActivityTokenRequest(code = "bad-code"), servletResponse)

        assertEquals(502, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
        assertNull(response.body!!.sessionToken)
        assertNull(response.body!!.accessToken)
        assertNull(servletResponse.getHeader("Set-Cookie"))
    }

    @Test
    fun `successful exchange returns both tokens and sets the session cookie`() {
        every { sessions.exchange("good-code") } returns
            ActivitySessions.Issued(sessionToken = "act_s3ss10n", accessToken = "discord-tok")

        val response = controller.token(ActivityTokenRequest(code = "good-code"), servletResponse)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals("act_s3ss10n", body.sessionToken)
        assertEquals("discord-tok", body.accessToken)

        val cookie = servletResponse.getHeader("Set-Cookie")!!
        assertTrue(cookie.startsWith("${ActivityTokenAuthFilter.COOKIE_NAME}=act_s3ss10n;"))
        // Third-party iframe context: SameSite=None + Secure are load-bearing.
        assertTrue(cookie.contains("SameSite=None"))
        assertTrue(cookie.contains("Secure"))
        assertTrue(cookie.contains("HttpOnly"))
    }
}
