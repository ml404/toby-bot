package web.template

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

/**
 * Renders the two legacy "pick a server" pages (intro picker `guilds.html`
 * and moderation picker `moderation-guilds.html`) through a real Spring
 * Thymeleaf engine after their inline <style> blocks were extracted to the
 * shared `/css/guild-picker.css`. Guards against a malformed
 * head/extraCss wiring and confirms the card grid + empty state still
 * render (these pages had no render coverage before).
 */
class GuildPickerTemplateRenderTest {

    private val servletContext = MockServletContext()
    private val webApp = JakartaServletWebApplication.buildApplication(servletContext)

    private val engine: SpringTemplateEngine = SpringTemplateEngine().apply {
        val appCtx = GenericApplicationContext().also { it.refresh() }
        setTemplateResolver(SpringResourceTemplateResolver().apply {
            setApplicationContext(appCtx)
            prefix = "classpath:/templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        })
    }

    private fun render(view: String, vars: Map<String, Any?>): String {
        val exchange = webApp.buildExchange(MockHttpServletRequest(servletContext), MockHttpServletResponse())
        val ctx = WebContext(exchange).apply { vars.forEach { (k, v) -> setVariable(k, v) } }
        return engine.process(view, ctx)
    }

    private fun guild(id: String, name: String, icon: String?) =
        mapOf("id" to id, "name" to name, "iconUrl" to icon)

    @Test
    fun `moderation picker renders the shared stylesheet, card grid and reveal`() {
        val html = render(
            "moderation-guilds",
            mapOf(
                "guilds" to listOf(guild("1", "Alpha", "https://cdn/icon.png"), guild("2", "Beta", null)),
                "defaultGuildId" to null,
                "inviteUrl" to "https://invite",
                "username" to "op",
            ),
        )
        assertTrue(html.contains("/css/guild-picker.css")) { "shared stylesheet not linked:\n$html" }
        assertTrue(html.contains("guild-grid") && html.contains("data-reveal")) { "grid/reveal missing" }
        assertTrue(html.contains("Alpha") && html.contains("Beta"))
        assertTrue(html.contains("guild-icon-placeholder")) { "icon placeholder for icon-less guild missing" }
    }

    @Test
    fun `moderation picker renders the empty state with no guilds`() {
        val html = render(
            "moderation-guilds",
            mapOf("guilds" to emptyList<Any>(), "defaultGuildId" to null, "inviteUrl" to "https://invite", "username" to "op"),
        )
        assertTrue(html.contains("not a moderator anywhere")) { "empty hero missing:\n$html" }
    }

    @Test
    fun `intro picker renders the shared stylesheet, count pill and search`() {
        val html = render(
            "guilds",
            mapOf(
                "guilds" to listOf(guild("1", "Alpha", null)),
                "introCounts" to mapOf("1" to 2),
                "maxIntros" to 3,
                "defaultGuildId" to 1L,
                "inviteUrl" to "https://invite",
                "username" to "op",
            ),
        )
        assertTrue(html.contains("/css/guild-picker.css")) { "shared stylesheet not linked:\n$html" }
        assertTrue(html.contains("guild-count")) { "intro count pill missing" }
        assertTrue(html.contains("2 / 3")) { "count text missing:\n$html" }
        assertTrue(html.contains("guildSearch")) { "search box missing" }
        assertTrue(html.contains("data-reveal"))
    }

    @Test
    fun `intro picker renders the empty state with no guilds`() {
        val html = render(
            "guilds",
            mapOf("guilds" to emptyList<Any>(), "introCounts" to emptyMap<String, Int>(), "maxIntros" to 3,
                "defaultGuildId" to null, "inviteUrl" to "https://invite", "username" to "op"),
        )
        assertTrue(html.contains("isn't in any of your servers")) { "empty hero missing:\n$html" }
    }
}
