package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import web.service.EconomyGuildCard
import web.service.EconomyWebService
import web.util.DefaultGuildCookie

/**
 * The /casino/guilds picker is the navbar's landing page for every
 * minigame. These tests pin the auto-redirect rules:
 *
 *   - Without `game`, never auto-redirect: the picker also acts as a
 *     per-guild game index, so it has standalone value.
 *   - With a valid `game` slug AND single mutual guild OR a valid cookie
 *     anchor, redirect to `/casino/{id}/{game}` (skip the picker).
 *   - `pick=true` forces the picker regardless (back-link bypass).
 *   - Stale cookies (guild the user no longer shares) must be ignored,
 *     never 4xx'd.
 *   - Unknown game slugs are silently dropped — they're user-controlled
 *     input from a URL query, not trusted routing data.
 */
internal class CasinoControllerTest {

    private val discordId = 100L
    private val tokenValue = "tkn"
    private lateinit var economyWebService: EconomyWebService
    private lateinit var user: OAuth2User
    private lateinit var client: OAuth2AuthorizedClient
    private lateinit var request: HttpServletRequest
    private lateinit var model: Model
    private lateinit var controller: CasinoController

    @BeforeEach
    fun setup() {
        economyWebService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        val token: OAuth2AccessToken = mockk { every { tokenValue } returns this@CasinoControllerTest.tokenValue }
        client = mockk { every { accessToken } returns token }
        request = mockk(relaxed = true) { every { cookies } returns null }
        model = mockk(relaxed = true)
        controller = CasinoController(economyWebService)
    }

    private fun card(id: Long, name: String = "g$id") = EconomyGuildCard(
        id = id.toString(), name = name, iconUrl = null, price = null, coins = 0, credits = 0
    )

    private fun setGuilds(vararg ids: Long) {
        every { economyWebService.getGuildsWhereUserCanView(tokenValue, discordId) } returns ids.map { card(it) }
    }

    private fun cookieFor(guildId: Long) {
        every { request.cookies } returns arrayOf(Cookie(DefaultGuildCookie.COOKIE_NAME, guildId.toString()))
    }

    @Test
    fun `redirects to single mutual guild's game when game param is valid and no cookie`() {
        setGuilds(777L)

        val result = controller.guildList(client, user, game = "slots", pick = false, request = request, model = model)

        assertEquals("redirect:/casino/777/slots", result)
    }

    @Test
    fun `redirects to anchored guild when cookie is set and user is still a member`() {
        setGuilds(111L, 222L, 333L)
        cookieFor(222L)

        val result = controller.guildList(client, user, game = "roulette", pick = false, request = request, model = model)

        assertEquals("redirect:/casino/222/roulette", result)
    }

    @Test
    fun `falls back to single-guild redirect when cookie points to a guild user no longer shares`() {
        setGuilds(111L) // user only shares one guild now
        cookieFor(999L)  // cookie points to an unrelated/old guild

        val result = controller.guildList(client, user, game = "dice", pick = false, request = request, model = model)

        assertEquals("redirect:/casino/111/dice", result)
    }

    @Test
    fun `renders picker when cookie is stale and multiple guilds without anchor match`() {
        setGuilds(111L, 222L)
        cookieFor(999L) // user not in 999 anymore

        val result = controller.guildList(client, user, game = "slots", pick = false, request = request, model = model)

        assertEquals("casino-guilds", result)
        verify { model.addAttribute("intendedGame", "slots") }
        verify { model.addAttribute("defaultGuildId", 999L) }
    }

    @Test
    fun `renders picker when multiple guilds and no cookie`() {
        setGuilds(111L, 222L)

        val result = controller.guildList(client, user, game = "slots", pick = false, request = request, model = model)

        assertEquals("casino-guilds", result)
        verify { model.addAttribute("intendedGame", "slots") }
        verify { model.addAttribute("defaultGuildId", null) }
    }

    @Test
    fun `renders picker even with valid cookie when pick=true`() {
        setGuilds(111L, 222L)
        cookieFor(222L)

        val result = controller.guildList(client, user, game = "slots", pick = true, request = request, model = model)

        assertEquals("casino-guilds", result)
        verify { model.addAttribute("defaultGuildId", 222L) }
    }

    @Test
    fun `does not redirect when game param is missing - picker is the index page`() {
        setGuilds(777L)

        val result = controller.guildList(client, user, game = null, pick = false, request = request, model = model)

        assertEquals("casino-guilds", result)
    }

    @Test
    fun `does not redirect when game param is not in the whitelist`() {
        setGuilds(777L)

        val result = controller.guildList(client, user, game = "../../etc/passwd", pick = false, request = request, model = model)

        assertEquals("casino-guilds", result)
        verify { model.addAttribute("intendedGame", null) }
    }

    @Test
    fun `game param is matched case-insensitively`() {
        setGuilds(777L)

        val result = controller.guildList(client, user, game = "SLOTS", pick = false, request = request, model = model)

        assertEquals("redirect:/casino/777/slots", result)
    }

    @Test
    fun `accepts every game slug rendered in the casino-guilds template`() {
        // If a slug listed in the template stops resolving, the auto-redirect
        // for that game silently falls back to "show picker" — this test is a
        // tripwire when the template adds or renames a game.
        val templateSlugs = listOf(
            "coinflip", "dice", "highlow", "keno", "roulette", "scratch", "slots",
            "plinko", "horse-racing", "wheel",
            "baccarat", "casinoholdem",
            "lottery",
        )
        setGuilds(42L)
        for (slug in templateSlugs) {
            val result = controller.guildList(client, user, game = slug, pick = false, request = request, model = model)
            assertEquals("redirect:/casino/42/$slug", result, "slug `$slug` should auto-redirect")
        }
    }

    @Test
    fun `renders picker when user has no mutual guilds`() {
        setGuilds() // empty

        val result = controller.guildList(client, user, game = "slots", pick = false, request = request, model = model)

        assertEquals("casino-guilds", result)
    }

    @Test
    fun `picker model carries intendedGame and defaultGuildId for template`() {
        setGuilds(111L, 222L)
        cookieFor(222L)

        controller.guildList(client, user, game = "wheel", pick = true, request = request, model = model)

        verify { model.addAttribute("intendedGame", "wheel") }
        verify { model.addAttribute("defaultGuildId", 222L) }
        verify { model.addAttribute(eq("guilds"), any()) }
    }

    @Test
    fun `whitelist constant matches expected slug set`() {
        // Belt-and-braces: pins the exact slug set so a typo in a template
        // link surfaces here too.
        assertEquals(
            setOf(
                "coinflip", "dice", "highlow", "keno", "roulette", "scratch", "slots",
                "plinko", "horse-racing", "wheel",
                "baccarat", "casinoholdem", "lottery",
            ),
            CasinoController.CASINO_GAME_SLUGS
        )
        assertTrue(CasinoController.gamePath(7L, "slots") == "/casino/7/slots")
    }
}
