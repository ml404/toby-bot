package web.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpResponse

class RedditTokenProviderTest {

    private fun response(status: Int, body: String): HttpResponse<String> =
        mockk<HttpResponse<String>>().also {
            every { it.statusCode() } returns status
            every { it.body() } returns body
        }

    @Test
    fun `unconfigured provider reports not configured and never touches the network`() {
        val http = mockk<HttpClient>()
        val provider = RedditTokenProvider(clientId = "", clientSecret = "  ", http = http)

        assertFalse(provider.isConfigured)
        assertNull(provider.bearerToken())
        verify(exactly = 0) { http.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    @Test
    fun `configured provider fetches and returns the access token`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returns
            response(200, """{"access_token":"tok123","token_type":"bearer","expires_in":3600}""")
        val provider = RedditTokenProvider(clientId = "id", clientSecret = "secret", http = http)

        assertTrue(provider.isConfigured)
        assertEquals("tok123", provider.bearerToken())
    }

    @Test
    fun `token is cached and only fetched once within its lifetime`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returns
            response(200, """{"access_token":"tok123","expires_in":3600}""")
        var now = 0L
        val provider = RedditTokenProvider(clientId = "id", clientSecret = "secret", http = http, nowMs = { now })

        assertEquals("tok123", provider.bearerToken())
        now = 1_000L
        assertEquals("tok123", provider.bearerToken())
        verify(exactly = 1) { http.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    @Test
    fun `token is refreshed once it nears expiry`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(200, """{"access_token":"first","expires_in":3600}"""),
            response(200, """{"access_token":"second","expires_in":3600}"""),
        )
        var now = 0L
        val provider = RedditTokenProvider(clientId = "id", clientSecret = "secret", http = http, nowMs = { now })

        assertEquals("first", provider.bearerToken())
        now = 3_600_000L // past (expiry - refresh skew)
        assertEquals("second", provider.bearerToken())
        verify(exactly = 2) { http.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    @Test
    fun `a token response without an access_token yields null`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returns
            response(401, """{"error":"invalid_grant"}""")
        val provider = RedditTokenProvider(clientId = "id", clientSecret = "secret", http = http)

        assertNull(provider.bearerToken())
    }

    @Test
    fun `a transport failure yields null rather than propagating`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } throws java.io.IOException("reddit down")
        val provider = RedditTokenProvider(clientId = "id", clientSecret = "secret", http = http)

        assertNull(provider.bearerToken())
    }
}
