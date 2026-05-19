package web.template

import common.notification.NotificationChannelKind
import common.notification.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import web.controller.NotificationPreferencesController.MatrixCell
import web.controller.NotificationPreferencesController.MatrixRow

/**
 * Renders the real `templates/preferences-notifications.html` and
 * asserts every kind × surface combination produces the correct cell
 * type — a toggle for supported pairs, a `—` placeholder for unsupported
 * ones — plus the per-cell `data-kind` / `data-surface` / `data-opt-in`
 * attributes the JS uses to wire toggle clicks. Catches regressions a
 * pure controller test misses (the controller's matrix is correct; the
 * template's rendering of it might not be).
 */
class PreferencesNotificationsTemplateTest {

    private val servletContext = MockServletContext()
    private val webApp = JakartaServletWebApplication.buildApplication(servletContext)
    private val engine: SpringTemplateEngine = SpringTemplateEngine().apply {
        val appCtx = GenericApplicationContext().also { it.refresh() }
        val resolver = SpringResourceTemplateResolver().apply {
            setApplicationContext(appCtx)
            prefix = "classpath:/templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        }
        setTemplateResolver(resolver)
    }

    /** Build a complete matrix for every kind (mirrors NotificationPreferencesController.page). */
    private fun matrix(): List<MatrixRow> = NotificationChannelKind.entries.map { kind ->
        MatrixRow(
            kind = kind.name,
            displayName = kind.displayName,
            description = kind.description,
            cells = Surface.entries.map { surface ->
                if (!kind.supports(surface)) {
                    MatrixCell.Placeholder(surface = surface.name)
                } else {
                    MatrixCell.Toggle(
                        surface = surface.name,
                        optIn = kind.defaultOptIn(surface),
                        isDefault = true,
                    )
                }
            }
        )
    }

    private fun render(): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            setVariable("guildId", 42L)
            setVariable("guildName", "Test Guild")
            setVariable("username", "tester")
            setVariable("surfaces", Surface.entries.map { it.name })
            setVariable("matrix", matrix())
        }
        return engine.process("preferences-notifications", ctx)
    }

    @Test
    fun `every kind renders a row keyed by data-kind`() {
        val html = render()
        NotificationChannelKind.entries.forEach { kind ->
            assertTrue(
                html.contains("data-kind=\"${kind.name}\""),
                "${kind.name} must render a row"
            )
        }
    }

    @Test
    fun `every Surface renders a column header`() {
        val html = render()
        Surface.entries.forEach { surface ->
            assertTrue(
                html.contains(">${surface.name}<"),
                "${surface.name} must appear as a column header"
            )
        }
    }

    @Test
    fun `every supported (kind, surface) renders a toggle with data attributes`() {
        val html = render()
        var toggleCount = 0
        NotificationChannelKind.entries.forEach { kind ->
            kind.supportedSurfaces.forEach { surface ->
                val expected = "data-kind=\"${kind.name}\""
                val surfaceMarker = "data-surface=\"${surface.name}\""
                // Look for a button that carries BOTH attributes — the
                // toggle is a `<button class="notif-toggle" ...>` with
                // data-kind + data-surface.
                val combined = "notif-toggle"
                assertTrue(
                    html.contains(combined) && html.contains(expected) && html.contains(surfaceMarker),
                    "${kind.name} × ${surface.name} must render a notif-toggle button"
                )
                toggleCount++
            }
        }
        val expectedTotal = NotificationChannelKind.entries.sumOf { it.supportedSurfaces.size }
        assertEquals(expectedTotal, toggleCount, "internal counter sanity")
    }

    @Test
    fun `unsupported (kind, surface) renders a notif-placeholder cell`() {
        val html = render()
        // INTRO_PROMPT supports DM only — CHANNEL and PUSH must be placeholders.
        // Find the INTRO_PROMPT row by data-kind, then check it contains
        // a placeholder marker.
        val rowStart = html.indexOf("data-kind=\"INTRO_PROMPT\"")
        assertTrue(rowStart > 0, "INTRO_PROMPT row not found in rendered HTML")
        val rowEnd = html.indexOf("</tr>", rowStart) + "</tr>".length
        val rowHtml = html.substring(rowStart, rowEnd)
        assertTrue(
            rowHtml.contains("notif-placeholder"),
            "INTRO_PROMPT row must contain notif-placeholder cells for CHANNEL+PUSH"
        )
    }

    @Test
    fun `PUSH footnote is present so users know the surface is opt-in-ready`() {
        val html = render()
        assertTrue(
            html.contains("notif-push-footnote"),
            "page must explain that PUSH is opt-in-ready (no provider wired)"
        )
    }

    @Test
    fun `page references the api and preferences-notifications JS bundles`() {
        val html = render()
        assertTrue(html.contains("/js/api.js"), "page must load api.js for CSRF-aware POST")
        assertTrue(html.contains("/js/preferences-notifications.js"), "page must load the toggle JS")
        assertTrue(html.contains("/js/push-subscribe.js"), "page must load the push-subscribe toggle JS")
    }

    @Test
    fun `page renders the browser-push toggle section the JS hooks into`() {
        val html = render()
        // push-subscribe.js looks up `[data-push-toggle]` to wire the
        // enable/disable button — if this attribute disappears the JS
        // silently no-ops.
        assertTrue(
            html.contains("data-push-toggle"),
            "page must contain the data-push-toggle section the JS wires into"
        )
        assertTrue(
            html.contains("push-toggle-btn"),
            "page must contain the .push-toggle-btn the JS targets"
        )
    }

    @Test
    fun `guildId appears as a data-attribute on the main container for JS routing`() {
        val html = render()
        assertTrue(
            html.contains("data-guild-id=\"42\""),
            "JS reads guildId from data-guild-id on <main>"
        )
    }
}
