package web.template

import common.mtg.MtgCommandRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the Playwright fixture for `/magic` honest.
 *
 * The browser tests drive a static copy of the page (Playwright serves
 * `src/test/e2e/fixtures/` — no Spring, no database), so the copy has to be
 * the **real** template's output or the tests drift away from what ships.
 * Rather than hand-maintaining that HTML, this renders the template and
 * compares it to the committed fixture.
 *
 * When the template legitimately changes, refresh the fixture with:
 *
 * ```
 * ./gradlew :web:test --tests '*MagicE2eFixtureTest*' -Dfixture.refresh=true
 * ```
 *
 * The rendered page is deliberately the logged-out one: the browser tests
 * stub the API rather than authenticating, and the logged-out render is the
 * one every visitor gets.
 */
class MagicE2eFixtureTest {

    private val renderer = TemplateRenderer()

    private val fixture: Path =
        Path.of("src/test/e2e/fixtures/magic.html").toAbsolutePath()

    /** Mirrors CubeController.mtgCommands(). */
    private val mtgCmd = mapOf(
        "cardLookup" to MtgCommandRef.CARD_LOOKUP,
        "deckLegality" to MtgCommandRef.DECK_LEGALITY,
        "mtgSet" to MtgCommandRef.MTG_SET,
        "mtgRule" to MtgCommandRef.MTG_RULE,
        "pricewatchAdd" to MtgCommandRef.PRICEWATCH_ADD,
    )

    private fun renderPage(): String =
        renderer.render("magic", mapOf("loggedIn" to false, "mtgCmd" to mtgCmd)) + "\n"

    @Test
    fun `the committed browser fixture is what the template actually renders`() {
        val rendered = renderPage()

        if (System.getProperty("fixture.refresh") == "true") {
            Files.createDirectories(fixture.parent)
            Files.writeString(fixture, rendered)
            return
        }

        val committed = if (Files.exists(fixture)) Files.readString(fixture) else ""
        assertEquals(rendered, committed) {
            "The /magic Playwright fixture is stale — the template has changed since it was " +
                "generated, so the browser tests are driving a page that no longer ships. " +
                "Refresh it with: ./gradlew :web:test --tests '*MagicE2eFixtureTest*' -Dfixture.refresh=true"
        }
    }

    @Test
    fun `the fixture carries the assets the browser tests need`() {
        val html = renderPage()

        // Served from the fixtures directory, so these resolve as long as
        // build-fixtures.js has copied the real css/ and js/ next to it.
        assertEquals(true, html.contains("/js/magic-format.js"))
        assertEquals(true, html.contains("/js/magic-render.js"))
        assertEquals(true, html.contains("/css/magic.css"))
    }
}
