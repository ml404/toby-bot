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
import web.service.HomeStatsService

/**
 * Renders the real home.html through a Spring Thymeleaf engine to prove the
 * live-stats expressions actually evaluate — the hero "members reached"
 * tile, the woven-in hero subtitle clause, and the "by the numbers"
 * members cell. HomeFeatureCardsTemplateTest only string-scans the raw
 * template, so it can't catch a broken SpEL expression; this can.
 */
class HomeStatsRenderTest {

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

    private fun stats(servers: Int, members: Long) = HomeStatsService.HomeStats(
        serverCount = servers, memberCount = members, commandCount = 98, gameCount = 19,
        minigameCount = 12, minigameNames = "slots, dice", casinoGameCount = 15,
        pvpGameCount = 4, pvpGameNames = "duel, rps", configKeyCount = 58,
        achievementCount = 15, notificationKindCount = 8,
    )

    private fun render(servers: Int, members: Long): String {
        val exchange = webApp.buildExchange(MockHttpServletRequest(servletContext), MockHttpServletResponse())
        val ctx = WebContext(exchange).apply {
            setVariable("homeStats", stats(servers, members))
            setVariable("inviteUrl", "https://invite")
        }
        return engine.process("home", ctx)
    }

    @Test
    fun `hero strip, woven copy and numbers strip all surface the member count`() {
        val html = render(servers = 1280, members = 2_500_000L)

        // Hero stats tile + label.
        assertTrue(html.contains("Members")) { "Members tile label missing" }
        // Comma-formatted member count appears (hero tile / numbers strip / copy).
        assertTrue(html.contains("2,500,000")) { "formatted member count missing:\n$html" }
        // Woven hero subtitle clause with correct pluralisation.
        assertTrue(html.contains("Already serving 2,500,000 members across 1,280 servers.")) {
            "hero subtitle clause not rendered as expected:\n$html"
        }
        assertTrue(html.contains("Members reached")) { "numbers-strip members cell missing" }
    }

    @Test
    fun `hero subtitle uses singular server and hides when no servers`() {
        val singular = render(servers = 1, members = 42L)
        assertTrue(singular.contains("across 1 server.")) { "singular 'server' not used:\n$singular" }

        val none = render(servers = 0, members = 0L)
        assertTrue(!none.contains("Already serving")) { "subtitle clause should hide when serverCount is 0" }
    }
}
