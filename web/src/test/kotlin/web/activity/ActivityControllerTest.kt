package web.activity

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ConcurrentModel

class ActivityControllerTest {

    private val sessions = mockk<ActivitySessions>()
    private val controller = ActivityController(sessions, clientId = "  12345 ")
    private val servletResponse = MockHttpServletResponse()

    @Test
    fun `shell renders the activity template with the trimmed client id`() {
        val model = ConcurrentModel()

        val view = controller.shell(model)

        assertEquals("activity", view)
        assertEquals("12345", model.getAttribute("clientId"))
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
