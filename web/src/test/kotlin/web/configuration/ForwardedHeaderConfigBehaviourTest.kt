package web.configuration

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Proves the request-side half of [ForwardedHeaderConfig]: even with
 * `relativeRedirects=true` (which only changes how response Location
 * headers are written), the filter still rewrites the *request*'s
 * scheme/host/port from the `X-Forwarded-*` headers.
 *
 * That rewrite is what Spring Security reads when it builds the OAuth2
 * `redirect_uri` for the "Log in with Discord" button. Behind Heroku's
 * router the inbound request hits the dyno as `http://<internal>:8080`;
 * only after this filter runs does it look like
 * `https://www.toby-bot.co.uk`. If that ever stops happening the
 * redirect_uri sent to Discord reverts to the internal host and login
 * breaks — so this pins the behaviour directly, independent of filter
 * ordering (covered by [ForwardedHeaderConfigOrderTest]).
 */
internal class ForwardedHeaderConfigBehaviourTest {

    private val filter = ForwardedHeaderConfig().forwardedHeaderFilter().filter

    @Test
    fun `rewrites the request scheme, host and port from X-Forwarded headers`() {
        val request = MockHttpServletRequest("GET", "/oauth2/authorization/discord").apply {
            scheme = "http"
            serverName = "10.0.0.5"
            serverPort = 8080
            addHeader("X-Forwarded-Proto", "https")
            addHeader("X-Forwarded-Host", "www.toby-bot.co.uk")
            addHeader("X-Forwarded-Port", "443")
        }
        val chain = MockFilterChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        val forwarded = chain.request as HttpServletRequest
        assertEquals("https", forwarded.scheme, "scheme must come from X-Forwarded-Proto")
        assertEquals("www.toby-bot.co.uk", forwarded.serverName, "host must come from X-Forwarded-Host")
        assertEquals(443, forwarded.serverPort, "port must come from X-Forwarded-Port")
    }
}
