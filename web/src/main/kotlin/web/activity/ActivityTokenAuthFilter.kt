package web.activity

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates requests carrying an activity session token issued by
 * [ActivitySessionService]. Three carriers are accepted, in order:
 *
 *  - `Authorization: Bearer act_…` — fetch() calls from JS (api.js
 *    attaches it whenever an activity token is present).
 *  - `?activityToken=act_…` query param — full-page navigations inside
 *    the Activity iframe, where a header can't be attached.
 *  - `tobyActivitySession` cookie — set by the token exchange as a
 *    fallback for the same navigations (and EventSource, which can
 *    carry neither header nor bespoke params safely), in case the
 *    Discord proxy or a client webview drops query params.
 *
 * On a hit the security context gets a principal with the same attribute
 * shape (`id`, `username`) as the oauth2Login path, so every existing
 * controller, WebGuildAccess guard, and the XP interceptor work
 * unchanged. No-ops when the request is already authenticated (normal
 * browser session) or carries no recognisable token.
 *
 * Failure semantics differ by carrier:
 *  - An EXPLICIT token (header/query param) that doesn't resolve gets a
 *    self-contained 401 page telling the player to relaunch — inside
 *    the activity sandbox a bounce toward the login flow would
 *    eventually navigate to an external origin and Discord kills the
 *    iframe, so dead sessions must never reach the redirect machinery.
 *  - A stale COOKIE alone falls through unauthenticated (and is
 *    cleared) so it can never wedge normal dashboard browsing.
 *
 * CSRF note: only bearer-HEADER requests are CSRF-exempt (see
 * WebSecurityConfig). The cookie is sent automatically by browsers, so
 * cookie-carried auth deliberately keeps full CSRF protection — it
 * only needs to cover GETs anyway.
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
        if (existing != null && existing !is AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response)
            return
        }

        val explicitToken = explicitToken(request)
        val cookieToken = cookieToken(request)
        val token = explicitToken ?: cookieToken
        if (token == null) {
            filterChain.doFilter(request, response)
            return
        }

        val principal = sessions.resolve(token)
        if (principal != null) {
            val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
            auth.details = WebAuthenticationDetailsSource().buildDetails(request)
            SecurityContextHolder.getContext().authentication = auth
            filterChain.doFilter(request, response)
            return
        }

        // Dead cookie with no explicit token: clear it and continue
        // anonymously so a lingering cookie can't wedge the normal site.
        if (explicitToken == null) {
            response.addCookie(Cookie(COOKIE_NAME, "").apply {
                path = "/"
                maxAge = 0
                secure = true
                isHttpOnly = true
            })
            filterChain.doFilter(request, response)
            return
        }

        // Explicit-but-dead token: the caller is inside the activity (or
        // a stale tab). Page navigations get the relaunch page; JS/API
        // callers get a bare 401 their error handling already understands.
        if (wantsHtml(request)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "text/html;charset=UTF-8"
            response.writer.write(EXPIRED_PAGE)
        } else {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
        }
    }

    private fun explicitToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.removePrefix(BEARER_PREFIX).trim()
                .takeIf { it.startsWith(ActivitySessionService.TOKEN_PREFIX) }
        }
        return request.getParameter(QUERY_PARAM)
            ?.takeIf { it.startsWith(ActivitySessionService.TOKEN_PREFIX) }
    }

    private fun cookieToken(request: HttpServletRequest): String? =
        request.cookies
            ?.firstOrNull { it.name == COOKIE_NAME }
            ?.value
            ?.takeIf { it.startsWith(ActivitySessionService.TOKEN_PREFIX) }

    private fun wantsHtml(request: HttpServletRequest): Boolean {
        if (!request.method.equals("GET", ignoreCase = true)) return false
        return request.getHeader("Accept")?.contains("text/html") == true
    }

    companion object {
        const val QUERY_PARAM = "activityToken"
        const val COOKIE_NAME = "tobyActivitySession"
        private const val BEARER_PREFIX = "Bearer "

        // Self-contained (no template engine in the filter chain, and no
        // redirects allowed — see class doc). Styling kept inline so it
        // renders sanely even if static assets are unreachable.
        private val EXPIRED_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Session expired</title></head>
            <body style="background:#1e1f22;color:#dbdee1;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;text-align:center">
            <div><h1>🎰 Session expired</h1>
            <p>Your casino session has ended.<br>Close this activity and launch it again to keep playing.</p></div>
            </body>
            </html>
        """.trimIndent()
    }
}
