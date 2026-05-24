package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import web.service.HomeStatsService

class HomeControllerTest {

    private lateinit var controller: HomeController
    private lateinit var model: Model
    private lateinit var homeStatsService: HomeStatsService

    private val clientId = "test-client-id"
    // Pinning the *shape* of the invite URL — the bitmask itself is
    // owned by web.util.DiscordInvite. Asserting the exact
    // `permissions=N` string would couple this test to that constant
    // unnecessarily; instead we delegate the bitmask via the helper.
    private val expectedInviteUrl = web.util.DiscordInvite.urlFor(clientId)
    private val sampleStats = HomeStatsService.HomeStats(
        serverCount = 7,
        commandCount = 42,
        gameCount = 15,
        minigameCount = 12,
        minigameNames = "slots, dice",
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
    }

    @Test
    fun `home returns home view`() {
        val view = controller.home(null, model)

        assertEquals("home", view)
    }

    @Test
    fun `home adds inviteUrl to model`() {
        controller.home(null, model)

        verify { model.addAttribute("inviteUrl", expectedInviteUrl) }
    }

    @Test
    fun `home adds null username when user is not authenticated`() {
        controller.home(null, model)

        verify { model.addAttribute("username", null) }
    }

    @Test
    fun `home adds username when user is authenticated`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns "TestUser"

        controller.home(user, model)

        verify { model.addAttribute("username", "TestUser") }
    }

    @Test
    fun `home adds null username when authenticated user has no username attribute`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns null

        controller.home(user, model)

        verify { model.addAttribute("username", null) }
    }

    @Test
    fun `home adds homeStats from the stats service`() {
        controller.home(null, model)

        verify { homeStatsService.get() }
        verify { model.addAttribute("homeStats", sampleStats) }
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
