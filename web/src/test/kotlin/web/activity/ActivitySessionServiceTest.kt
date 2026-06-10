package web.activity

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpResponse

/**
 * Covers the SDK-code → Discord-token → session-token exchange and the
 * lifecycle of the in-memory session store. The two Discord calls (token
 * endpoint, /users/@me) are stubbed at the HttpClient seam, mirroring
 * RedditTokenProviderTest.
 */
class ActivitySessionServiceTest {

    private fun response(status: Int, body: String): HttpResponse<String> =
        mockk<HttpResponse<String>>().also {
            every { it.statusCode() } returns status
            every { it.body() } returns body
        }

    private val tokenJson = """{"access_token":"discord-tok","token_type":"Bearer","expires_in":3600}"""
    private val userJson = """{"id":"123","username":"tester"}"""

    @Test
    fun `exchange mints a resolvable session and returns the discord access token`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(200, tokenJson),
            response(200, userJson),
        )
        val service = ActivitySessionService(clientId = "id", clientSecret = "secret", http = http)

        val issued = service.exchange("the-code")

        assertNotNull(issued)
        assertEquals("discord-tok", issued!!.accessToken)
        assertTrue(issued.sessionToken.startsWith(ActivitySessionService.TOKEN_PREFIX))

        val principal = service.resolve(issued.sessionToken)
        assertNotNull(principal)
        assertEquals("123", principal!!.getAttribute<String>("id"))
        assertEquals("tester", principal.getAttribute<String>("username"))
    }

    @Test
    fun `unconfigured service refuses the exchange without touching the network`() {
        val http = mockk<HttpClient>()
        val service = ActivitySessionService(clientId = "", clientSecret = " ", http = http)

        assertNull(service.exchange("the-code"))
        verify(exactly = 0) { http.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    @Test
    fun `a failed token exchange yields null`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returns
            response(400, """{"error":"invalid_grant"}""")
        val service = ActivitySessionService(clientId = "id", clientSecret = "secret", http = http)

        assertNull(service.exchange("stale-code"))
    }

    @Test
    fun `a userinfo response without an id yields null`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(200, tokenJson),
            response(401, """{"message":"401: Unauthorized"}"""),
        )
        val service = ActivitySessionService(clientId = "id", clientSecret = "secret", http = http)

        assertNull(service.exchange("the-code"))
    }

    @Test
    fun `sessions expire with the discord token`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(200, """{"access_token":"discord-tok","expires_in":60}"""),
            response(200, userJson),
        )
        var now = 0L
        val service = ActivitySessionService(clientId = "id", clientSecret = "secret", http = http, nowMs = { now })

        val issued = service.exchange("the-code")!!
        assertNotNull(service.resolve(issued.sessionToken))

        now = 61_000L
        assertNull(service.resolve(issued.sessionToken))
    }

    @Test
    fun `unknown tokens resolve to null`() {
        val service = ActivitySessionService(clientId = "id", clientSecret = "secret", http = mockk())
        assertNull(service.resolve("act_nope"))
    }
}
