package web.template

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
import web.controller.CommandWikiController.CategoryView
import web.controller.CommandWikiController.CommandView
import web.controller.CommandWikiController.OptionView
import web.controller.CommandWikiController.SubcommandView

/**
 * Renders the real `templates/commands.html` + `fragments/commandCard.html`
 * against a Spring-backed Thymeleaf engine.
 *
 * Regression guard: the first cut of the page put `th:each` and
 * `th:replace` on the same `<div>` inside `.cmd-grid`. Thymeleaf's
 * attribute precedence (`th:replace` = 100, `th:each` = 200; lower number
 * runs first) evaluated the fragment substitution before the loop
 * variable was established, aborted the render mid-stream, and the
 * response truncated at the opening `<div class="cmd-grid">` tag —
 * Spring shipped a 200 OK with no `</main>`, no `</body>`, no script tag.
 * The fix hosts the loop on a `<th:block>` so the iterator and the
 * fragment call live on different elements.
 *
 * Pure unit speed: no Docker, no Spring Boot context. The view model is
 * already plain Kotlin so the fixture builds without touching JDA.
 */
class CommandsWikiTemplateRenderTest {

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

    private fun render(categories: List<CategoryView>): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            setVariable("categories", categories)
            setVariable("totalCommandCount", categories.sumOf { it.commands.size })
        }
        return engine.process("commands", ctx)
    }

    private fun musicFixture() = CategoryView(
        slug = "music",
        title = "Music",
        icon = "🎵",
        blurb = "Play, queue, and control music.",
        commands = listOf(
            CommandView(
                name = "play",
                description = "Play a song",
                options = listOf(
                    OptionView(
                        name = "query",
                        description = "Song name or URL",
                        typeLabel = "text",
                        required = true,
                        choices = emptyList()
                    )
                ),
                subCommands = emptyList(),
                searchKey = "play play a song query"
            ),
            CommandView(
                name = "queue",
                description = "Manage the queue",
                options = emptyList(),
                subCommands = listOf(
                    SubcommandView(name = "show", description = "Show the queue", options = emptyList()),
                    SubcommandView(name = "clear", description = "Clear the queue", options = emptyList())
                ),
                searchKey = "queue manage the queue show clear"
            )
        )
    )

    @Test
    fun `cmd-grid loop renders one cmd-card per command and the response closes cleanly`() {
        val html = render(listOf(musicFixture()))

        val cardCount = "<article class=\"cmd-card\"".toRegex().findAll(html).count()
        assertEquals(2, cardCount) {
            "expected two .cmd-card elements (one per command) — the th:each loop inside .cmd-grid " +
                "must iterate without aborting the render. If this fails with 0 cards, the " +
                "th:each + th:replace combination has regressed to sharing a single element again; " +
                "host the loop on a <th:block> as in fragments/guildListPage.html. Render:\n$html"
        }

        assertTrue(html.contains("</body>")) {
            "rendered HTML must include </body> — a truncated response means Thymeleaf aborted " +
                "mid-stream and Spring sent the partial output as a 200 OK. Render:\n$html"
        }
    }

    @Test
    fun `each command's slash name and description render`() {
        val html = render(listOf(musicFixture()))

        assertTrue(html.contains(">/play<")) { "/play pill should be rendered:\n$html" }
        assertTrue(html.contains(">/queue<")) { "/queue pill should be rendered:\n$html" }
        assertTrue(html.contains("Play a song")) { "/play description should be rendered:\n$html" }
    }

    @Test
    fun `friendly type label renders instead of the raw enum name`() {
        val html = render(listOf(musicFixture()))

        // The /play command has one option `query` of type "text" (friendly label).
        // The controller's friendlyTypeLabel(OptionType) map converts STRING -> text;
        // if that map regresses or the template uses opt.type.name instead of
        // opt.typeLabel, this assertion catches it.
        val cardCount = "<span class=\"opt-type\">text</span>".toRegex().findAll(html).count()
        assertTrue(cardCount >= 1) {
            "option type pill should render the friendly label 'text', not 'STRING':\n$html"
        }
    }

    @Test
    fun `subcommands of options-less command render as inset rows`() {
        val html = render(listOf(musicFixture()))

        // /queue has no top-level options but two subcommands (show / clear).
        // The fragment renders those as `.sub-row` strips with /<cmd> <sub> pills.
        assertTrue(html.contains(">/queue show<")) { "/queue show sub-pill should render:\n$html" }
        assertTrue(html.contains(">/queue clear<")) { "/queue clear sub-pill should render:\n$html" }
        assertTrue(html.contains("\"cmd-sub-count\"")) {
            "the subcommand count chip should appear on /queue's card:\n$html"
        }
    }
}
