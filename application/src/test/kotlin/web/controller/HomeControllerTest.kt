package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import web.service.HomeStatsService

class HomeControllerTest {

    private lateinit var controller: HomeController
    private lateinit var model: Model
    private lateinit var request: HttpServletRequest
    private lateinit var homeStatsService: HomeStatsService

    private val clientId = "test-client-id"
    // Pinning the *shape* of the invite URL — the bitmask itself is
    // owned by web.util.DiscordInvite. Asserting the exact
    // `permissions=N` string would couple this test to that constant
    // unnecessarily; instead we delegate the bitmask via the helper.
    private val expectedInviteUrl = web.util.DiscordInvite.urlFor(clientId)
    private val sampleStats = HomeStatsService.HomeStats(
        serverCount = 7,
        memberCount = 12_345L,
        commandCount = 42,
        gameCount = 19,
        minigameCount = 12,
        minigameNames = "slots, dice",
        casinoGameCount = 15,
        pvpGameCount = 4,
        pvpGameNames = "duel, rps, tictactoe, connect4",
        configKeyCount = 58,
        achievementCount = 15,
        notificationKindCount = 8,
    )

    @BeforeEach
    fun setup() {
        homeStatsService = mockk(relaxed = true)
        every { homeStatsService.get() } returns sampleStats
        controller = HomeController(clientId, homeStatsService)
        model = mockk(relaxed = true)
        request = mockk(relaxed = true)
    }

    // Plain browser visits carry none of the Discord Activity SDK launch
    // params, so frameId is null and the homepage renders as before.
    private fun home(user: OAuth2User? = null, frameId: String? = null) =
        controller.home(user, frameId, request, model)

    @Test
    fun `home returns home view`() {
        val view = home()

        assertEquals("home", view)
    }

    @Test
    fun `home adds inviteUrl to model`() {
        home()

        verify { model.addAttribute("inviteUrl", expectedInviteUrl) }
    }

    @Test
    fun `home adds null username when user is not authenticated`() {
        home()

        verify { model.addAttribute("username", null) }
    }

    @Test
    fun `home adds username when user is authenticated`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns "TestUser"

        home(user)

        verify { model.addAttribute("username", "TestUser") }
    }

    @Test
    fun `home adds null username when authenticated user has no username attribute`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns null

        home(user)

        verify { model.addAttribute("username", null) }
    }

    @Test
    fun `home adds homeStats from the stats service`() {
        home()

        verify { homeStatsService.get() }
        verify { model.addAttribute("homeStats", sampleStats) }
    }

    @Test
    fun `a discord activity launch on the root is forwarded to the shell with its params intact`() {
        // Discord always loads the proxy root "/" with the SDK params in
        // the query string; the Embedded App SDK needs every one of them
        // (frame_id, instance_id, platform, ...) present on the shell URL.
        every { request.queryString } returns "instance_id=i-1&frame_id=abc&platform=mobile"

        val view = home(frameId = "abc")

        assertEquals("redirect:/activity?instance_id=i-1&frame_id=abc&platform=mobile", view)
        // The redirect branch must not touch the model or the stats
        // service — it renders nothing.
        verify(exactly = 0) { homeStatsService.get() }
    }

    @Test
    fun `terms returns terms view`() {
        val view = controller.terms(null, model)

        assertEquals("terms", view)
    }

    @Test
    fun `terms adds null username when user is not authenticated`() {
        controller.terms(null, model)

        verify { model.addAttribute("username", null) }
    }

    @Test
    fun `terms adds username when user is authenticated`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns "TestUser"

        controller.terms(user, model)

        verify { model.addAttribute("username", "TestUser") }
    }

    @Test
    fun `terms adds null username when authenticated user has no username attribute`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns null

        controller.terms(user, model)

        verify { model.addAttribute("username", null) }
    }

    @Test
    fun `privacy returns privacy view`() {
        val view = controller.privacy(null, model)

        assertEquals("privacy", view)
    }

    @Test
    fun `privacy adds null username when user is not authenticated`() {
        controller.privacy(null, model)

        verify { model.addAttribute("username", null) }
    }

    @Test
    fun `privacy adds username when user is authenticated`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns "TestUser"

        controller.privacy(user, model)

        verify { model.addAttribute("username", "TestUser") }
    }

    @Test
    fun `privacy adds null username when authenticated user has no username attribute`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns null

        controller.privacy(user, model)

        verify { model.addAttribute("username", null) }
    }
}
