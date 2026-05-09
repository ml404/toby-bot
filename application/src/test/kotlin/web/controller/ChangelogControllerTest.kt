package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model

class ChangelogControllerTest {

    private lateinit var controller: ChangelogController
    private lateinit var model: Model

    @BeforeEach
    fun setup() {
        controller = ChangelogController()
        model = mockk(relaxed = true)
    }

    @Test
    fun `changelog returns changelog view`() {
        val view = controller.changelog(null, model)

        assertEquals("changelog", view)
    }

    @Test
    fun `changelog seeds entries on the model`() {
        controller.changelog(null, model)

        verify { model.addAttribute("entries", ChangelogController.ENTRIES) }
    }

    @Test
    fun `changelog has at least one entry seeded`() {
        // Sanity check: the controller would compile with an empty list,
        // so guard against a future regression that empties the seed.
        assertFalse(
            ChangelogController.ENTRIES.isEmpty(),
            "ChangelogController.ENTRIES must contain at least one curated entry"
        )
    }

    @Test
    fun `changelog skips username when user is not authenticated`() {
        controller.changelog(null, model)

        // The view is public, so anon visitors see only the entries.
        // The navbar fragment handles the null-username case.
        verify(exactly = 0) { model.addAttribute("username", any()) }
    }

    @Test
    fun `changelog adds username when user is authenticated`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns "TestUser"

        controller.changelog(user, model)

        verify { model.addAttribute("username", "TestUser") }
    }
}
