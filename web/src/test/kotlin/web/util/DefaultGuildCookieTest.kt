package web.util

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins the cookie shape and parse rules. The cookie is the *only* source
 * of truth for an anchored default guild, so a malformed value MUST
 * yield null (treat as "no preference") rather than throwing — picker
 * pages have to keep rendering even when a stale browser session sends
 * gibberish.
 */
internal class DefaultGuildCookieTest {

    private fun requestWith(vararg cookies: Cookie, secure: Boolean = false): HttpServletRequest =
        mockk {
            every { this@mockk.cookies } returns if (cookies.isEmpty()) null else cookies
            every { isSecure } returns secure
        }

    @Test
    fun `read returns null when no cookies present`() {
        assertNull(DefaultGuildCookie.read(requestWith()))
    }

    @Test
    fun `read returns null when other cookies present but ours is missing`() {
        assertNull(DefaultGuildCookie.read(requestWith(Cookie("session", "abc"))))
    }

    @Test
    fun `read returns guildId when cookie value is a valid long`() {
        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, "1234567890"))
        assertEquals(1234567890L, DefaultGuildCookie.read(req))
    }

    @Test
    fun `read returns null when cookie value is non-numeric`() {
        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, "not-a-number"))
        assertNull(DefaultGuildCookie.read(req))
    }

    @Test
    fun `read returns null when cookie value overflows Long`() {
        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, "99999999999999999999"))
        assertNull(DefaultGuildCookie.read(req))
    }

    @Test
    fun `read returns null when cookie value is empty`() {
        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, ""))
        assertNull(DefaultGuildCookie.read(req))
    }

    @Test
    fun `write sets cookie with 1 year max age, root path, and SameSite=Lax`() {
        val req = requestWith(secure = false)
        val response = mockk<HttpServletResponse>(relaxed = true)
        val captured = slot<Cookie>()
        every { response.addCookie(capture(captured)) } returns Unit

        DefaultGuildCookie.write(req, response, 42L)

        val c = captured.captured
        assertEquals(DefaultGuildCookie.COOKIE_NAME, c.name)
        assertEquals("42", c.value)
        assertEquals("/", c.path)
        assertEquals(60 * 60 * 24 * 365, c.maxAge)
        assertEquals("Lax", c.getAttribute("SameSite"))
    }

    @Test
    fun `write marks cookie Secure when request is HTTPS`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val captured = slot<Cookie>()
        every { response.addCookie(capture(captured)) } returns Unit

        DefaultGuildCookie.write(requestWith(secure = true), response, 7L)

        assertEquals(true, captured.captured.secure)
    }

    @Test
    fun `write leaves Secure off for plain HTTP (so localhost dev works)`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val captured = slot<Cookie>()
        every { response.addCookie(capture(captured)) } returns Unit

        DefaultGuildCookie.write(requestWith(secure = false), response, 7L)

        assertEquals(false, captured.captured.secure)
    }

    @Test
    fun `clear writes an expired cookie at the same path`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val captured = slot<Cookie>()
        every { response.addCookie(capture(captured)) } returns Unit

        DefaultGuildCookie.clear(requestWith(), response)

        val c = captured.captured
        assertEquals(DefaultGuildCookie.COOKIE_NAME, c.name)
        assertEquals("/", c.path)
        assertEquals(0, c.maxAge)
        verify(exactly = 1) { response.addCookie(any()) }
    }

    @Test
    fun `sanitizeRedirect accepts in-app paths`() {
        assertEquals("/casino/guilds?game=slots", DefaultGuildCookie.sanitizeRedirect("/casino/guilds?game=slots"))
        assertEquals("/leaderboards", DefaultGuildCookie.sanitizeRedirect("/leaderboards"))
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("/"))
    }

    @Test
    fun `sanitizeRedirect falls back to default for external URLs`() {
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("https://evil.example/path"))
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("http://evil.example"))
    }

    @Test
    fun `sanitizeRedirect falls back to default for protocol-relative URLs`() {
        // `//evil.example` would otherwise become `https://evil.example` in the
        // browser — the leading double-slash is the classic redirect bypass.
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("//evil.example/path"))
    }

    @Test
    fun `sanitizeRedirect falls back to default for backslash bypass`() {
        // Some browsers normalise /\ to // — block that variant too.
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("/\\evil.example"))
    }

    @Test
    fun `sanitizeRedirect falls back to default for blank or null`() {
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect(null))
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect(""))
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("   "))
    }

    @Test
    fun `sanitizeRedirect falls back to default for relative paths`() {
        // `casino/guilds` without leading slash could resolve relative to the
        // current request — we want strict absolute-from-root only.
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("casino/guilds"))
        assertEquals("/", DefaultGuildCookie.sanitizeRedirect("./casino"))
    }

    @Test
    fun `sanitizeRedirect honours custom fallback`() {
        assertEquals("/home", DefaultGuildCookie.sanitizeRedirect(null, "/home"))
        assertEquals("/home", DefaultGuildCookie.sanitizeRedirect("https://evil.example", "/home"))
    }
}
