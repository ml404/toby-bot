package web.controller

import database.service.music.MusicFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
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
 * Coverage for the auto-redirect rules added to `/intro/guilds`. The
 * rest of [IntroWebController] (file upload, undo, REST endpoints) has
 * its own untouched behaviour and isn't in scope here — these tests
 * pin only the picker redirect contract.
 */
internal class IntroWebControllerGuildListTest {

    private val discordId = 100L
    private val tokenValue = "tkn"

    private lateinit var introWebService: IntroWebService
    private lateinit var musicFileService: MusicFileService
    private lateinit var user: OAuth2User
    private lateinit var client: OAuth2AuthorizedClient
    private lateinit var request: HttpServletRequest
    private lateinit var controller: IntroWebController

    @BeforeEach
    fun setup() {
        introWebService = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        val token: OAuth2AccessToken = mockk { every { tokenValue } returns this@IntroWebControllerGuildListTest.tokenValue }
        client = mockk { every { accessToken } returns token }
        request = mockk(relaxed = true) { every { cookies } returns null }
        controller = IntroWebController(introWebService, musicFileService, "test-client-id")
    }

    private fun setMutualGuilds(vararg ids: Long) {
        every { introWebService.getMutualGuilds(tokenValue) } returns ids.map { GuildInfo(it.toString(), "g$it", null) }
        every { introWebService.getIntroCountsForGuilds(discordId, any()) } returns emptyMap()
    }

    private fun cookieFor(guildId: Long) {
        every { request.cookies } returns arrayOf(Cookie(DefaultGuildCookie.COOKIE_NAME, guildId.toString()))
    }

    @Test
    fun `guildList redirects to single mutual guild's intro page`() {
        setMutualGuilds(777L)
        val result = controller.guildList(client, user, pick = false, request = request, model = mockk(relaxed = true))
        assertEquals("redirect:/intro/777", result)
    }

    @Test
    fun `guildList redirects to anchored guild when cookie set and user still a member`() {
        setMutualGuilds(111L, 222L)
        cookieFor(222L)
        val result = controller.guildList(client, user, pick = false, request = request, model = mockk(relaxed = true))
        assertEquals("redirect:/intro/222", result)
    }

    @Test
    fun `guildList renders picker when pick=true bypasses auto-redirect`() {
        setMutualGuilds(111L, 222L)
        cookieFor(222L)
        val model: Model = mockk(relaxed = true)

        val result = controller.guildList(client, user, pick = true, request = request, model = model)

        assertEquals("guilds", result)
        verify { model.addAttribute("defaultGuildId", 222L) }
    }

    @Test
    fun `guildList ignores stale cookie pointing to a guild user no longer shares`() {
        setMutualGuilds(111L, 222L)
        cookieFor(999L)
        val model: Model = mockk(relaxed = true)

        val result = controller.guildList(client, user, pick = false, request = request, model = model)

        assertEquals("guilds", result)
        verify { model.addAttribute("defaultGuildId", 999L) }
    }

    @Test
    fun `guildList renders empty picker when user has no mutual guilds`() {
        setMutualGuilds()
        val result = controller.guildList(client, user, pick = false, request = request, model = mockk(relaxed = true))
        assertEquals("guilds", result)
    }
}
