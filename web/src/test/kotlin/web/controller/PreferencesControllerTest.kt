package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import web.service.GuildInfo
import web.service.IntroWebService
import web.util.DefaultGuildCookie

/**
 * Pins the contract of the cookie-toggle endpoints. Two non-obvious
 * pieces of behaviour to guard:
 *
 *  1. **Membership check on SET** — anchoring a guild the user isn't in
 *     would be silently ignored at the next picker visit (the auto-
 *     redirect re-validates), but the cleaner contract is to refuse the
 *     write outright. A crafted POST shouldn't be able to plant a cookie.
 *
 *  2. **Redirect sanitisation** — `redirect` is reflected into the
 *     `Location` header. An attacker who can talk a user into clicking
 *     a star button (CSRF-protected by Spring already) plus a poisoned
 *     `redirect=https://evil.example` would otherwise get an open-
 *     redirect. We funnel every value through [DefaultGuildCookie.sanitizeRedirect].
 */
internal class PreferencesControllerTest {

    private val tokenValue = "tkn-abc"
    private lateinit var introWebService: IntroWebService
    private lateinit var user: OAuth2User
    private lateinit var client: OAuth2AuthorizedClient
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var controller: PreferencesController

    @BeforeEach
    fun setup() {
        introWebService = mockk()
        user = mockk(relaxed = true) {
            every { getAttribute<String>("username") } returns "tester"
        }
        val token: OAuth2AccessToken = mockk { every { tokenValue } returns this@PreferencesControllerTest.tokenValue }
        client = mockk { every { accessToken } returns token }
        request = mockk(relaxed = true) {
            every { isSecure } returns false
            every { cookies } returns null
        }
        response = mockk(relaxed = true)
        controller = PreferencesController(introWebService)
    }

    private fun guildInfo(id: Long, name: String = "g$id") = GuildInfo(id.toString(), name, null)

    @Test
    fun `page exposes mutual guilds and current default to the model`() {
        val cookieReq: HttpServletRequest = mockk(relaxed = true) {
            every { isSecure } returns false
            every { cookies } returns arrayOf(Cookie(DefaultGuildCookie.COOKIE_NAME, "222"))
        }
        every { introWebService.getMutualGuilds(tokenValue) } returns listOf(guildInfo(111L), guildInfo(222L))
        val model: Model = mockk(relaxed = true)

        val view = controller.page(client = client, user = user, request = cookieReq, model = model)

        assertEquals("preferences", view)
        verify { model.addAttribute("defaultGuildId", 222L) }
        verify { model.addAttribute(eq("guilds"), any()) }
    }

    @Test
    fun `page returns empty guild list for anonymous user`() {
        val model: Model = mockk(relaxed = true)

        val view = controller.page(client = client, user = null, request = request, model = model)

        assertEquals("preferences", view)
        verify(exactly = 0) { introWebService.getMutualGuilds(any()) }
    }

    @Test
    fun `setDefaultGuild writes cookie when user is a member`() {
        every { introWebService.getMutualGuilds(tokenValue) } returns listOf(guildInfo(111L), guildInfo(222L))
        val captured = slot<Cookie>()
        every { response.addCookie(capture(captured)) } returns Unit

        val result = controller.setDefaultGuild(
            client = client,
            user = user,
            guildId = 222L,
            redirect = "/casino/guilds?game=slots&pick=true",
            request = request,
            response = response,
        )

        assertEquals("redirect:/casino/guilds?game=slots&pick=true", result)
        assertEquals(DefaultGuildCookie.COOKIE_NAME, captured.captured.name)
        assertEquals("222", captured.captured.value)
    }

    @Test
    fun `setDefaultGuild does not write cookie when user is not a member`() {
        every { introWebService.getMutualGuilds(tokenValue) } returns listOf(guildInfo(111L))

        val result = controller.setDefaultGuild(
            client = client,
            user = user,
            guildId = 999L,
            redirect = "/leaderboards",
            request = request,
            response = response,
        )

        assertEquals("redirect:/leaderboards", result)
        verify(exactly = 0) { response.addCookie(any()) }
    }

    @Test
    fun `setDefaultGuild redirects without writing when user is anonymous`() {
        val result = controller.setDefaultGuild(
            client = client,
            user = null,
            guildId = 222L,
            redirect = "/leaderboards",
            request = request,
            response = response,
        )

        assertEquals("redirect:/leaderboards", result)
        verify(exactly = 0) { response.addCookie(any()) }
        verify(exactly = 0) { introWebService.getMutualGuilds(any()) }
    }

    @Test
    fun `setDefaultGuild rejects external redirect targets`() {
        every { introWebService.getMutualGuilds(tokenValue) } returns listOf(guildInfo(111L))
        every { response.addCookie(any()) } returns Unit

        val result = controller.setDefaultGuild(
            client = client,
            user = user,
            guildId = 111L,
            redirect = "https://evil.example/path",
            request = request,
            response = response,
        )

        // Cookie still written (the request is otherwise valid), but the
        // redirect target is sanitised to the safe fallback.
        assertEquals("redirect:/", result)
    }

    @Test
    fun `setDefaultGuild rejects protocol-relative redirect`() {
        every { introWebService.getMutualGuilds(tokenValue) } returns listOf(guildInfo(111L))
        every { response.addCookie(any()) } returns Unit

        val result = controller.setDefaultGuild(
            client = client,
            user = user,
            guildId = 111L,
            redirect = "//evil.example",
            request = request,
            response = response,
        )

        assertEquals("redirect:/", result)
    }

    @Test
    fun `clearDefaultGuild clears cookie and redirects to sanitised target`() {
        val captured = slot<Cookie>()
        every { response.addCookie(capture(captured)) } returns Unit

        val result = controller.clearDefaultGuild(
            redirect = "/casino/guilds?pick=true",
            request = request,
            response = response,
        )

        assertEquals("redirect:/casino/guilds?pick=true", result)
        assertEquals(DefaultGuildCookie.COOKIE_NAME, captured.captured.name)
        assertEquals(0, captured.captured.maxAge)
    }

    @Test
    fun `clearDefaultGuild sanitises external redirect target`() {
        every { response.addCookie(any()) } returns Unit

        val result = controller.clearDefaultGuild(
            redirect = "https://evil.example/path",
            request = request,
            response = response,
        )

        assertEquals("redirect:/", result)
    }

    @Test
    fun `clearDefaultGuild redirects to fallback when redirect is null`() {
        every { response.addCookie(any()) } returns Unit

        val result = controller.clearDefaultGuild(
            redirect = null,
            request = request,
            response = response,
        )

        assertEquals("redirect:/", result)
        verify(exactly = 1) { response.addCookie(any()) }
    }
}
