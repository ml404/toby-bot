package web.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model

class ChangelogControllerTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private lateinit var controller: ChangelogController
    private lateinit var model: Model

    @BeforeEach
    fun setup() {
        controller = ChangelogController(objectMapper)
        model = mockk(relaxed = true)
    }

    @Test
    fun `changelog returns changelog view`() {
        val view = controller.changelog(null, model)

        assertEquals("changelog", view)
    }

    @Test
    fun `changelog adds the loaded entries onto the model`() {
        val captured = slot<List<ChangelogController.ChangelogEntry>>()
        every { model.addAttribute("entries", capture(captured)) } returns model

        controller.changelog(null, model)

        verify { model.addAttribute("entries", any<List<ChangelogController.ChangelogEntry>>()) }
        assertFalse(captured.captured.isEmpty(), "changelog.json should seed at least one entry")
        // Spot-check shape so a future schema drift surfaces here, not at
        // template-render time.
        val first = captured.captured.first()
        assertTrue(first.date.isNotBlank(), "first entry should have a date")
        assertTrue(first.title.isNotBlank(), "first entry should have a title")
        assertTrue(first.summary.isNotBlank(), "first entry should have a summary")
    }

    @Test
    fun `controller loads at least one entry from the classpath JSON`() {
        // Sanity check: if changelog.json is missing or malformed the
        // controller falls back to empty silently — guard against that
        // shipping unnoticed.
        assertTrue(
            controller.entryCount() > 0,
            "ChangelogController should load entries from $RESOURCE on the classpath"
        )
    }

    @Test
    fun `changelog skips username when user is not authenticated`() {
        controller.changelog(null, model)

        verify(exactly = 0) { model.addAttribute("username", any()) }
    }

    @Test
    fun `changelog adds username when user is authenticated`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns "TestUser"

        controller.changelog(user, model)

        verify { model.addAttribute("username", "TestUser") }
    }

    companion object {
        private const val RESOURCE = ChangelogController.CHANGELOG_RESOURCE
    }
}
