package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model

class HomeControllerTest {

    private lateinit var controller: HomeController
    private lateinit var model: Model

    private val clientId = "test-client-id"
    private val expectedInviteUrl =
        "https://discord.com/api/oauth2/authorize?client_id=$clientId&permissions=8&scope=bot%20applications.commands"

    @BeforeEach
    fun setup() {
        controller = HomeController(clientId)
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
}
