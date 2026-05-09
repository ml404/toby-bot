package web.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model

class ChangelogControllerTest {

    // Plain Jackson ObjectMapper — no jackson-module-kotlin import. The
    // kotlin module is on the runtime classpath (Spring Boot
    // auto-registers it on the wired-in ObjectMapper) but not on the
    // :application module's *test compile* classpath. ChangelogEntry's
    // data class has defaults on every field so the synthetic no-arg
    // constructor is enough for Jackson to deserialize without the
    // kotlin module.
    private val objectMapper: ObjectMapper = ObjectMapper()

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
        controller.changelog(null, model)

        verify { model.addAttribute("entries", any<List<ChangelogController.ChangelogEntry>>()) }
    }

    @Test
    fun `controller loads at least one entry from the classpath JSON`() {
        // Sanity check: if changelog.json is missing or malformed the
        // controller falls back to empty silently — guard against that
        // shipping unnoticed.
        assertTrue(
            controller.entryCount() > 0,
            "ChangelogController should load entries from ${ChangelogController.CHANGELOG_RESOURCE} on the classpath"
        )
    }

    @Test
    fun `changelog json entries have valid shape`() {
        // Read the resource directly (independent of the controller) so a
        // future schema drift surfaces here, not at template-render time.
        val url = javaClass.classLoader.getResource(ChangelogController.CHANGELOG_RESOURCE)
        assertNotNull(url, "${ChangelogController.CHANGELOG_RESOURCE} must be on the test classpath")
        val entries: List<ChangelogController.ChangelogEntry> = objectMapper.readValue(
            url!!.openStream(),
            object : TypeReference<List<ChangelogController.ChangelogEntry>>() {}
        )
        assertTrue(entries.isNotEmpty(), "changelog.json should seed at least one entry")
        val first = entries.first()
        assertTrue(first.date.isNotBlank(), "first entry should have a date")
        assertTrue(first.title.isNotBlank(), "first entry should have a title")
        assertTrue(first.summary.isNotBlank(), "first entry should have a summary")
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
}
