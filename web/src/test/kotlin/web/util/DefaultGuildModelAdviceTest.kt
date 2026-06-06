package web.util

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.BotOwnerAuthorizer

/**
 * The controller advice exposes the anchored guild id + name to every
 * model so the shared navbar fragment can render its "Default: X" pill
 * regardless of which controller produced the page. Two important rules:
 *
 *  - When the cookie points to a guild the bot was kicked from, the
 *    JDA lookup returns null and the advice must propagate that null
 *    (navbar hides the pill) instead of crashing the render.
 *  - When the cookie is missing or malformed, both attributes are null.
 */
internal class DefaultGuildModelAdviceTest {

    private val jda: JDA = mockk()
    private val botOwnerAuthorizer = BotOwnerAuthorizer("777")
    private val advice = DefaultGuildModelAdvice(jda, botOwnerAuthorizer)

    private fun requestWith(vararg cookies: Cookie): HttpServletRequest = mockk {
        every { this@mockk.cookies } returns if (cookies.isEmpty()) null else cookies
        every { isSecure } returns false
    }

    @Test
    fun `currentDefaultGuildId returns cookie value when valid`() {
        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, "555"))
        assertEquals(555L, advice.currentDefaultGuildId(req))
    }

    @Test
    fun `currentDefaultGuildId returns null when no cookie`() {
        assertNull(advice.currentDefaultGuildId(requestWith()))
    }

    @Test
    fun `currentDefaultGuildName resolves through JDA when guild exists`() {
        val guild = mockk<Guild> { every { name } returns "My Server" }
        every { jda.getGuildById(555L) } returns guild

        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, "555"))
        assertEquals("My Server", advice.currentDefaultGuildName(req))
    }

    @Test
    fun `currentDefaultGuildName returns null when cookie points to a guild the bot is no longer in`() {
        every { jda.getGuildById(555L) } returns null

        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, "555"))
        assertNull(advice.currentDefaultGuildName(req))
    }

    @Test
    fun `currentDefaultGuildName returns null when cookie missing`() {
        assertNull(advice.currentDefaultGuildName(requestWith()))
    }

    @Test
    fun `currentDefaultGuildName returns null when cookie malformed`() {
        val req = requestWith(Cookie(DefaultGuildCookie.COOKIE_NAME, "garbage"))
        assertNull(advice.currentDefaultGuildName(req))
    }

    @Test
    fun `isBotOwner is true for a configured operator id`() {
        val user = mockk<OAuth2User> { every { getAttribute<String>("id") } returns "777" }
        assertTrue(advice.isBotOwner(user))
    }

    @Test
    fun `isBotOwner is false for a non-operator id`() {
        val user = mockk<OAuth2User> { every { getAttribute<String>("id") } returns "123" }
        assertFalse(advice.isBotOwner(user))
    }

    @Test
    fun `isBotOwner is false for an anonymous (null) user`() {
        assertFalse(advice.isBotOwner(null))
    }
}
