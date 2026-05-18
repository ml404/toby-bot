package web.util

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * One-year browser cookie carrying the user's preferred guild id for
 * the casino / leaderboard / intro picker pages. When set and the user
 * is still a member of that guild, those pickers skip themselves and
 * land on the deep link directly.
 *
 * Per-browser, not per-Discord-account — picked over a DB-backed setting
 * because the feature is a navigation nicety, not durable user state.
 * Signing in from a different device just means re-anchoring there.
 */
object DefaultGuildCookie {

    const val COOKIE_NAME = "toby_default_guild"

    private const val MAX_AGE_SECONDS = 60 * 60 * 24 * 365

    /**
     * Returns the guild id from the request cookie, or null if absent /
     * malformed. Treat anything that doesn't parse as no preference —
     * a corrupted cookie should never 500 the picker.
     */
    fun read(request: HttpServletRequest): Long? {
        val cookie = request.cookies?.firstOrNull { it.name == COOKIE_NAME } ?: return null
        return cookie.value?.toLongOrNull()
    }

    fun write(request: HttpServletRequest, response: HttpServletResponse, guildId: Long) {
        val cookie = Cookie(COOKIE_NAME, guildId.toString()).apply {
            path = "/"
            maxAge = MAX_AGE_SECONDS
            secure = request.isSecure
            isHttpOnly = true
            setAttribute("SameSite", "Lax")
        }
        response.addCookie(cookie)
    }

    fun clear(request: HttpServletRequest, response: HttpServletResponse) {
        val cookie = Cookie(COOKIE_NAME, "").apply {
            path = "/"
            maxAge = 0
            secure = request.isSecure
            isHttpOnly = true
            setAttribute("SameSite", "Lax")
        }
        response.addCookie(cookie)
    }

    /**
     * Whitelist filter for the post-toggle redirect target. Accepts only
     * in-app paths so `?redirect=https://evil.example` can't bounce the
     * user off-site after we set the cookie. Falls back to `/` for any
     * value that isn't a clean single-slash relative path.
     */
    fun sanitizeRedirect(raw: String?, fallback: String = "/"): String {
        if (raw.isNullOrBlank()) return fallback
        if (!raw.startsWith("/")) return fallback
        if (raw.startsWith("//")) return fallback
        if (raw.startsWith("/\\")) return fallback
        return raw
    }
}
