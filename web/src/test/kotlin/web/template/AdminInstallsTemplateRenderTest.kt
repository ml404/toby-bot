package web.template

import org.junit.jupiter.api.Assertions.assertFalse
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
import web.service.AdminInstallsService

/**
 * Renders the real `templates/admin-installs.html` against a Spring-backed
 * Thymeleaf engine (same SpringEL dialect prod uses) so the per-row
 * expressions — the mode-badge `th:classappend` ternary, the
 * icon/placeholder branch, the owner-name fallback, and the installed-at
 * date column — are exercised, not just the controller wiring.
 */
class AdminInstallsTemplateRenderTest {

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

    private fun render(installs: List<AdminInstallsService.InstallRow>): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            setVariable("installs", installs)
            setVariable("username", "operator")
            setVariable("isBotOwner", true)
        }
        return engine.process("admin-installs", ctx)
    }

    private fun row(
        guildId: String,
        name: String,
        ownerId: String = "1",
        ownerName: String? = "Owner",
        icon: String? = null,
        mode: String = "express",
        installedAt: Long? = 1_700_000_000_000L,
    ) = AdminInstallsService.InstallRow(
        guildId = guildId, guildName = name, iconUrl = icon,
        ownerId = ownerId, ownerName = ownerName, memberCount = 7,
        installMode = mode, installedAtMillis = installedAt,
    )

    @Test
    fun `express and custom installs render their mode badges with the right modifier class`() {
        val html = render(
            listOf(
                row("10", "Alpha", mode = "express"),
                row("20", "Beta", mode = "custom"),
            )
        )
        assertTrue(html.contains("mode-express")) { "express badge class missing:\n$html" }
        assertTrue(html.contains("mode-custom")) { "custom badge class missing:\n$html" }
        assertTrue(html.contains("Alpha") && html.contains("Beta"))
        // 2 installs counted in the header.
        assertTrue(html.contains(">2<") || html.contains("2 total"))
    }

    @Test
    fun `legacy install renders the legacy badge and an em-dash for the missing date`() {
        val html = render(listOf(row("30", "Gamma", mode = AdminInstallsService.LEGACY, installedAt = null)))
        assertTrue(html.contains("mode-legacy")) { "legacy badge class missing:\n$html" }
        assertTrue(html.contains("legacy/unknown"))
        assertTrue(html.contains("&mdash;") || html.contains("—")) { "missing-date dash not rendered:\n$html" }
    }

    @Test
    fun `uncached owner falls back to showing the owner id`() {
        val html = render(listOf(row("40", "Delta", ownerId = "9988", ownerName = null)))
        assertTrue(html.contains("9988")) { "owner id fallback missing:\n$html" }
    }

    @Test
    fun `guild without an icon renders the initial placeholder instead of an img`() {
        val html = render(listOf(row("50", "Echo", icon = null)))
        assertTrue(html.contains("installs-icon-placeholder")) { "icon placeholder missing:\n$html" }
    }

    @Test
    fun `empty install list renders the empty-state hero`() {
        val html = render(emptyList())
        assertTrue(html.contains("No installs yet")) { "empty hero missing:\n$html" }
        // The CSS class name lives in the <style> block regardless; assert on the
        // actual table element instead.
        assertFalse(html.contains("<table")) { "table should not render when there are no installs:\n$html" }
    }
}
