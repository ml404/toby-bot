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
import web.service.TitleShopEntry
import web.service.TitleShopView

/**
 * Renders the real `templates/titles.html` against a Spring-backed
 * Thymeleaf engine (SpringEL — same expression dialect the bot uses in
 * prod) and asserts the level-gate UI: the `Lvl X` badge, the `locked`
 * modifier class, disabled Buy / Buy-with-TOBY buttons, and the tooltip
 * text. Catches regressions a pure-controller unit test cannot — the
 * `th:disabled`, `th:title`, and `th:classappend` expressions all live in
 * the template, not in Kotlin code.
 */
class TitlesTemplateRenderTest {

    private val servletContext = MockServletContext()
    private val webApp = JakartaServletWebApplication.buildApplication(servletContext)

    private val engine: SpringTemplateEngine = SpringTemplateEngine().apply {
        // SpringResourceTemplateResolver needs an ApplicationContext to
        // resolve `classpath:/templates/...` URIs. A bare
        // GenericApplicationContext is enough — we don't load any beans.
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

    private fun render(view: TitleShopView, guildId: Long = 42L, username: String = "tester"): String {
        // The page's head fragment uses context-relative `@{...}` links,
        // which require an IWebContext at render time. A mock servlet
        // exchange is the minimal way to satisfy that.
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            setVariable("titleShop", view)
            setVariable("guildId", guildId.toString())
            setVariable("username", username)
            setVariable("errorMessage", null)
            setVariable("successMessage", null)
        }
        return engine.process("titles", ctx)
    }

    private fun rowOf(html: String, titleId: Long): String {
        val marker = "data-title-id=\"$titleId\""
        val start = html.indexOf(marker).also {
            require(it >= 0) { "row for title $titleId not in:\n$html" }
        }
        val rowStart = html.lastIndexOf("<tr", start)
        val rowEnd = html.indexOf("</tr>", start) + "</tr>".length
        return html.substring(rowStart, rowEnd)
    }

    @Test
    fun `gated title below required level renders Lvl badge, locked class, disabled Buy with tooltip`() {
        val view = TitleShopView(
            catalog = listOf(
                TitleShopEntry(id = 1L, label = "🌱 Sprout", cost = 200L, description = null, colorHex = null, requiredLevel = 5),
            ),
            ownedTitleIds = HashSet<Long>(),
            equippedTitleId = null,
            balance = 10_000L,
            tobyCoins = 0L,
            marketPrice = 0.0,
            actorLevel = 0,
        )

        val html = render(view)
        val row = rowOf(html, 1L)

        assertTrue(row.contains("Lvl 5")) { "row should display the required level badge:\n$row" }
        assertTrue(row.contains("locked")) { "badge should carry the locked class when below required level:\n$row" }
        assertTrue(row.contains("Requires Level 5") && row.contains("you are 0")) {
            "row should include the tooltip text:\n$row"
        }
        assertTrue(row.contains("title-buy")) { "buy button should still render (visible-but-disabled):\n$row" }
        assertTrue(row.contains("disabled")) { "buy button should be disabled when level-locked:\n$row" }
    }

    @Test
    fun `gated title at or above required level renders Lvl badge without locked class and enables Buy`() {
        val view = TitleShopView(
            catalog = listOf(
                TitleShopEntry(id = 1L, label = "🌱 Sprout", cost = 200L, description = null, colorHex = null, requiredLevel = 5),
            ),
            ownedTitleIds = HashSet<Long>(),
            equippedTitleId = null,
            balance = 1_000L,
            tobyCoins = 0L,
            marketPrice = 0.0,
            actorLevel = 5,
        )

        val html = render(view)
        val row = rowOf(html, 1L)

        assertTrue(row.contains("Lvl 5")) { "row should still show the level requirement:\n$row" }
        // Badge class shouldn't gain the `locked` modifier.
        val badgeStart = row.indexOf("class=\"badge muted")
        assertTrue(badgeStart >= 0) { "badge span should exist for gated title:\n$row" }
        val badgeEnd = row.indexOf(">", badgeStart)
        val badgeTag = row.substring(badgeStart, badgeEnd)
        assertFalse(badgeTag.contains("locked")) {
            "badge should not carry the locked class when at or above required level:\n$badgeTag"
        }
        val buyButtonStart = row.indexOf("class=\"btn title-buy\"")
        val buyButtonEnd = row.indexOf(">", buyButtonStart)
        val buyButton = row.substring(buyButtonStart, buyButtonEnd)
        assertFalse(buyButton.contains("disabled")) {
            "buy button should be enabled when level requirement is met and balance covers cost:\n$buyButton"
        }
    }

    @Test
    fun `ungated title shows no Lvl badge and an enabled Buy when balance covers cost`() {
        val view = TitleShopView(
            catalog = listOf(
                TitleShopEntry(id = 7L, label = "⭐ Comrade", cost = 100L, description = null, colorHex = null, requiredLevel = 0),
            ),
            ownedTitleIds = HashSet<Long>(),
            equippedTitleId = null,
            balance = 500L,
            tobyCoins = 0L,
            marketPrice = 0.0,
            actorLevel = 0,
        )

        val html = render(view)
        val row = rowOf(html, 7L)

        assertFalse(row.contains("Lvl ")) { "ungated title should not render the Lvl badge:\n$row" }
        assertFalse(row.contains("locked")) { "ungated title should not carry the locked class:\n$row" }
        val buyButtonStart = row.indexOf("class=\"btn title-buy\"")
        val buyButtonEnd = row.indexOf(">", buyButtonStart)
        val buyButton = row.substring(buyButtonStart, buyButtonEnd)
        assertFalse(buyButton.contains("disabled")) {
            "buy button should be enabled for ungated title with enough credits:\n$buyButton"
        }
    }

    @Test
    fun `level-locked title disables the Buy-with-TOBY button too`() {
        val view = TitleShopView(
            catalog = listOf(
                TitleShopEntry(id = 9L, label = "👑 Grandmaster", cost = 250_000L, description = null, colorHex = null, requiredLevel = 100),
            ),
            ownedTitleIds = HashSet<Long>(),
            equippedTitleId = null,
            balance = 0L,
            tobyCoins = 1_000_000L, // plenty of TOBY — without the level gate the button would be enabled
            marketPrice = 1.0,
            liquidationCapacity = 1_000_000L, // can cover — so only the level gate disables it
            actorLevel = 50,
        )

        val html = render(view)
        val row = rowOf(html, 9L)

        val buyTobyStart = row.indexOf("class=\"btn title-buy-toby\"")
        assertTrue(buyTobyStart >= 0) { "Buy-with-TOBY button should render:\n$row" }
        val buyTobyEnd = row.indexOf(">", buyTobyStart)
        val buyTobyTag = row.substring(buyTobyStart, buyTobyEnd)
        assertTrue(buyTobyTag.contains("disabled")) {
            "Buy-with-TOBY should be disabled when level-locked even with sufficient TOBY:\n$buyTobyTag"
        }
        assertTrue(buyTobyTag.contains("Requires Level 100") && buyTobyTag.contains("you are 50")) {
            "tooltip should describe the level gate, not the TOBY price:\n$buyTobyTag"
        }
    }

    @Test
    fun `owned title shows Equip button regardless of level gate`() {
        val view = TitleShopView(
            catalog = listOf(
                TitleShopEntry(id = 1L, label = "🌱 Sprout", cost = 200L, description = null, colorHex = null, requiredLevel = 5),
            ),
            ownedTitleIds = setOf(1L),
            equippedTitleId = null,
            balance = 0L,
            tobyCoins = 0L,
            marketPrice = 0.0,
            actorLevel = 0, // below the gate, but already owns — should be equippable
        )

        val html = render(view)
        val row = rowOf(html, 1L)

        assertTrue(row.contains("title-equip")) {
            "owned title should expose an Equip button even when below the original gate:\n$row"
        }
        assertFalse(row.contains("title-buy\"")) {
            "owned title should not show a Buy button:\n$row"
        }
    }
}
