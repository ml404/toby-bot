package web.activity

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import web.util.discordIdOrNull

class ActivityTokenAuthFilterTest {

    private val principal: OAuth2User = DefaultOAuth2User(
        setOf(SimpleGrantedAuthority("OAUTH2_USER")),
        mapOf("id" to "123", "username" to "tester"),
        "id",
    )

    private lateinit var sessions: ActivitySessions
    private lateinit var filter: ActivityTokenAuthFilter
    private lateinit var chain: MockFilterChain

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        sessions = mockk()
        filter = ActivityTokenAuthFilter(sessions)
        chain = MockFilterChain()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun run(request: MockHttpServletRequest): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, chain)
        return response
    }

    @Test
    fun `bearer header authenticates the request as the session's discord user`() {
        every { sessions.resolve("act_tok") } returns principal
        val request = MockHttpServletRequest("POST", "/casino/42/slots/spin")
        request.addHeader("Authorization", "Bearer act_tok")

        run(request)

        val auth = SecurityContextHolder.getContext().authentication!!
        assertEquals(123L, (auth.principal as OAuth2User).discordIdOrNull())
        assertNotNull(chain.request) // request continued down the chain
    }

    @Test
    fun `query param authenticates page navigations`() {
        every { sessions.resolve("act_tok") } returns principal
        val request = MockHttpServletRequest("GET", "/casino/42/slots")
        request.setParameter(ActivityTokenAuthFilter.QUERY_PARAM, "act_tok")

        run(request)

        val auth = SecurityContextHolder.getContext().authentication!!
        assertEquals(123L, (auth.principal as OAuth2User).discordIdOrNull())
    }

    @Test
    fun `session cookie authenticates page navigations when params are lost`() {
        every { sessions.resolve("act_tok") } returns principal
        val request = MockHttpServletRequest("GET", "/casino/42/slots")
        request.setCookies(Cookie(ActivityTokenAuthFilter.COOKIE_NAME, "act_tok"))

        run(request)

        val auth = SecurityContextHolder.getContext().authentication!!
        assertEquals(123L, (auth.principal as OAuth2User).discordIdOrNull())
    }

    @Test
    fun `an explicit dead token on a page GET renders the relaunch page instead of redirecting`() {
        every { sessions.resolve("act_dead") } returns null
        val request = MockHttpServletRequest("GET", "/casino/42/slots")
        request.setParameter(ActivityTokenAuthFilter.QUERY_PARAM, "act_dead")
        request.addHeader("Accept", "text/html,application/xhtml+xml")

        val response = run(request)

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("Session expired"))
        assertNull(chain.request) // never reached the redirect machinery
    }

    @Test
    fun `an explicit dead token on a JSON call is a bare 401`() {
        every { sessions.resolve("act_dead") } returns null
        val request = MockHttpServletRequest("POST", "/casino/42/slots/spin")
        request.addHeader("Authorization", "Bearer act_dead")
        request.addHeader("Accept", "application/json")

        val response = run(request)

        assertEquals(401, response.status)
        assertEquals("", response.contentAsString)
        assertNull(chain.request)
    }

    @Test
    fun `a stale cookie alone is cleared and the request continues anonymously`() {
        every { sessions.resolve("act_stale") } returns null
        val request = MockHttpServletRequest("GET", "/leaderboards")
        request.setCookies(Cookie(ActivityTokenAuthFilter.COOKIE_NAME, "act_stale"))

        val response = run(request)

        assertNull(SecurityContextHolder.getContext().authentication)
        assertNotNull(chain.request) // normal browsing unaffected
        val cleared = response.cookies.first { it.name == ActivityTokenAuthFilter.COOKIE_NAME }
        assertEquals(0, cleared.maxAge)
    }

    @Test
    fun `tokens without the activity prefix are ignored without a store lookup`() {
        val request = MockHttpServletRequest("GET", "/casino/42/slots")
        request.addHeader("Authorization", "Bearer some-jwt")

        run(request)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly = 0) { sessions.resolve(any()) }
    }

    @Test
    fun `an existing session authentication is left untouched`() {
        val existing = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        SecurityContextHolder.getContext().authentication = existing
        val request = MockHttpServletRequest("GET", "/casino/42/slots")
        request.addHeader("Authorization", "Bearer act_tok")

        run(request)

        assertSame(existing, SecurityContextHolder.getContext().authentication)
        verify(exactly = 0) { sessions.resolve(any()) }
    }
}
