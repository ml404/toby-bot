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
 * `casino-jackpot.js`.
 *
 * Updated to understand shared script fragments (e.g.
 * `fragments/casinoMinigame :: scripts`) so we don't get false
 * negatives when scripts are pulled in indirectly via Thymeleaf.
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
                // Skip fragment definitions themselves
                .filter {
                    !it.toString().endsWith("fragments/casino.html") &&
                            !it.toString().contains("/fragments/")
                }
                .forEach { path ->
                    val text = Files.readString(path)

                    if (text.contains("fragments/casino :: jackpotBanner")) {
                        out += TemplateScan(
                            path = path,
                            loadsCasinoJackpotJs = loadsCasinoJackpotJs(text),
                        )
                    }
                }
        }

        return out
    }

    /**
     * Determines whether a template loads casino-jackpot.js either:
     *  1. Directly via a <script> tag
     *  2. Indirectly via a shared Thymeleaf fragment that we know provides it
     */
    private fun loadsCasinoJackpotJs(text: String): Boolean {
        val loadsDirectly = text.contains("/js/casino-jackpot.js")

        val loadsViaSharedFragment = text.contains("th:replace") &&
                text.contains("fragments/casinoMinigame :: scripts")

        return loadsDirectly || loadsViaSharedFragment
    }

    private data class TemplateScan(
        val path: Path,
        val loadsCasinoJackpotJs: Boolean,
    )
}