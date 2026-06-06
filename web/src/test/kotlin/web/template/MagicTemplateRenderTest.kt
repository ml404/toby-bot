package web.template

import common.mtg.MtgCommandRef
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
 * Renders the real `templates/magic.html` (the Magic toolkit page) against a
 * Spring-backed Thymeleaf engine — no Docker, no Spring Boot context.
 *
 * Regression guard for a production 500: the page's `headSeo(...)` call had an
 * apostrophe escaped SQL-style (`card''s`) in its description literal, but
 * Thymeleaf escapes apostrophes inside `'...'` with `\'` — so the expression
 * failed to parse and `/magic` threw a 500 at render time. The controller test
 * only asserts the returned view name ("cube"), so it never rendered the
 * template and never caught it. This test actually processes the template, so
 * any broken Thymeleaf expression in the page or its fragments fails here.
 */
class MagicTemplateRenderTest {

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

    // Mirrors CubeController.mtgCommands() — the page reads command names from
    // this model attribute (sourced from MtgCommandRef) rather than hardcoding.
    private val mtgCmd = mapOf(
        "cardLookup" to MtgCommandRef.CARD_LOOKUP,
        "deckLegality" to MtgCommandRef.DECK_LEGALITY,
        "mtgSet" to MtgCommandRef.MTG_SET,
        "mtgRule" to MtgCommandRef.MTG_RULE,
        "pricewatchAdd" to MtgCommandRef.PRICEWATCH_ADD,
    )

    private fun render(vars: Map<String, Any?>): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            setVariable("mtgCmd", mtgCmd)
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process("magic", ctx)
    }

    @Test
    fun `anonymous render completes with the SEO description and closes cleanly`() {
        val html = render(mapOf("loggedIn" to false))

        // Proves headSeo(...) parsed and rendered — the failure mode was a 500 here.
        assertTrue(html.contains("<meta name=\"description\"")) { "expected a meta description from headSeo:\n${html.take(600)}" }
        assertTrue(html.contains("Build and balance Magic")) { "expected the page description text" }
        assertTrue(html.contains("og:title")) { "expected Open Graph tags from headSeo" }
        // A truncated render = Thymeleaf aborted mid-stream; Spring would ship a partial 200.
        assertTrue(html.contains("</body>")) { "rendered HTML must close </body>; got truncated output" }
    }

    @Test
    fun `the tab groups render`() {
        val html = render(mapOf("loggedIn" to true, "username" to "tester"))

        assertTrue(html.contains("Build a cube")) { "expected the 'Build a cube' tab group label" }
        assertTrue(html.contains("Card &amp; deck tools") || html.contains("Card & deck tools")) {
            "expected the 'Card & deck tools' tab group label"
        }
        // The logged-in price-watch form is present.
        assertTrue(html.contains("data-form=\"watch\"")) { "expected the price-watch form for a logged-in user" }
    }

    @Test
    fun `command names render from the MtgCommandRef model attribute`() {
        val html = render(mapOf("loggedIn" to true, "username" to "tester"))

        // The page copy pulls the command names from the model (single source of
        // truth), so a command rename can't leave the prose stale.
        assertTrue(html.contains(MtgCommandRef.CARD_LOOKUP)) { "expected '${MtgCommandRef.CARD_LOOKUP}' in the card tab copy" }
        assertTrue(html.contains(MtgCommandRef.DECK_LEGALITY)) { "expected '${MtgCommandRef.DECK_LEGALITY}' in the legality tab copy" }
        assertTrue(html.contains(MtgCommandRef.MTG_SET)) { "expected '${MtgCommandRef.MTG_SET}' in the reference tab copy" }
        assertTrue(html.contains(MtgCommandRef.MTG_RULE)) { "expected '${MtgCommandRef.MTG_RULE}' in the reference tab copy" }
        assertTrue(html.contains(MtgCommandRef.PRICEWATCH_ADD)) { "expected '${MtgCommandRef.PRICEWATCH_ADD}' in the price-watch tab copy" }
    }

    @Test
    fun `a shared cube preloads its list`() {
        val html = render(mapOf("loggedIn" to false, "sharedName" to "My Cube", "sharedCards" to "Lightning Bolt"))
        assertTrue(html.contains("My Cube")) { "expected the shared cube banner name" }
    }
}
