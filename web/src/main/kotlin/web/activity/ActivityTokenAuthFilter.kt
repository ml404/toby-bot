package web.activity

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates requests carrying an activity session token issued by
 * [ActivitySessionService]. Two carriers are accepted:
 *
 *  - `Authorization: Bearer act_…` — used by fetch() calls from JS
 *    (api.js attaches it whenever an activity token is present).
 *  - `?activityToken=act_…` query param — used for full-page navigations
 *    inside the Activity iframe, where a header can't be attached. The
 *    token is opaque, short-lived, and the URL never leaves the
 *    `*.discordsays.com` sandbox, so the leak surface is the proxy's
 *    request log — acceptable for the ephemeral sessions involved.
 *
 * On a hit the security context gets a principal with the same attribute
 * shape (`id`, `username`) as the oauth2Login path, so every existing
 * controller, WebGuildAccess guard, and the XP interceptor work unchanged.
 * No-ops when the request is already authenticated (normal browser
 * session) or carries no recognisable token.
 *
 * Deliberately NOT a @Component: Spring Boot auto-registers Filter beans
 * with the servlet container, which would run it twice. It's instantiated
 * once inside WebSecurityConfig's filter chain instead.
 */
class ActivityTokenAuthFilter(
    private val sessions: ActivitySessions,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val existing = SecurityContextHolder.getContext().authentication
        if (existing == null || existing is AnonymousAuthenticationToken) {
            extractToken(request)?.let { token ->
                sessions.resolve(token)?.let { principal ->
                    val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
                    auth.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.removePrefix(BEARER_PREFIX).trim()
                .takeIf { it.startsWith(ActivitySessionService.TOKEN_PREFIX) }
        }
        return request.getParameter(QUERY_PARAM)
            ?.takeIf { it.startsWith(ActivitySessionService.TOKEN_PREFIX) }
    }

    companion object {
        const val QUERY_PARAM = "activityToken"
        private const val BEARER_PREFIX = "Bearer "
    }
}
