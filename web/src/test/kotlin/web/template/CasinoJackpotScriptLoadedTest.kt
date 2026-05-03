package web.template

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Static scanner that asserts every page template embedding the
 * `~{fragments/casino :: jackpotBanner}` block also loads
 * `casino-jackpot.js`. Without that script, `window.TobyJackpot` is
 * undefined and the centralised `X-Jackpot-Pool` reader inside `api.js`
 * silently no-ops — the server stamps the new pool size onto every
 * casino response, but the banner stays stale until the next full page
 * reload.
 *
 * This is exactly the regression that landed blackjack and poker on the
 * "still doesn't update" pile after #382 fixed the [JackpotPoolHeaderAdvice]
 * `supports()` predicate. Slots, dice, coinflip, scratch, highlow, and
 * baccarat all loaded `casino-jackpot.js`; blackjack-{lobby,solo,table}
 * and poker-{lobby,table} did not.
 *
 * The unit + MockMvc tests for the advice can prove the header is being
 * sent. Only a template-level scan can prove the page actually does
 * something with it. A controller test that mocks the service layer can
 * never catch a missing `<script>` tag.
 */
class CasinoJackpotScriptLoadedTest {

    private val templatesRoot: Path = Paths.get("src/main/resources/templates")
        .takeIf { Files.exists(it) }
        ?: Paths.get("web/src/main/resources/templates")

    @Test
    fun `every template embedding the jackpot banner also loads casino-jackpot js`() {
        val pages = collectPagesEmbeddingJackpotBanner()
        assertTrue(
            pages.isNotEmpty(),
            "expected to find at least one page embedding the jackpotBanner fragment under $templatesRoot"
        )

        val missing = pages.filter { !it.loadsCasinoJackpotJs }
        if (missing.isNotEmpty()) {
            fail<Unit>(
                "Pages embed the jackpotBanner fragment but never load /js/casino-jackpot.js — " +
                    "the X-Jackpot-Pool header arrives but window.TobyJackpot is undefined, so the " +
                    "banner never updates after a casino action:\n" +
                    missing.joinToString("\n  - ", prefix = "  - ") {
                        templatesRoot.relativize(it.path).toString()
                    }
            )
        }
    }

    private fun collectPagesEmbeddingJackpotBanner(): List<TemplateScan> {
        val out = mutableListOf<TemplateScan>()
        Files.walk(templatesRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".html") }
                // The fragment definition itself lives in fragments/casino.html and
                // doesn't load its own script — only the pages that embed it need to.
                .filter { !it.toString().endsWith("fragments/casino.html") && !it.toString().contains("/fragments/") }
                .forEach { path ->
                    val text = Files.readString(path)
                    if (text.contains("fragments/casino :: jackpotBanner")) {
                        out += TemplateScan(
                            path = path,
                            loadsCasinoJackpotJs = text.contains("/js/casino-jackpot.js"),
                        )
                    }
                }
        }
        return out
    }

    private data class TemplateScan(
        val path: Path,
        val loadsCasinoJackpotJs: Boolean,
    )
}
