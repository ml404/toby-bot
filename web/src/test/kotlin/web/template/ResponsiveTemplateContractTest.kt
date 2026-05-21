package web.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Responsive / mobile contract for the user-facing templates.
 *
 * These are presence-only checks against the rendered Thymeleaf sources
 * — they don't spin up Spring. The point is to catch the three failure
 * modes that broke mobile pages in the past:
 *
 *  1. A new page is added but its `<head>` fragment forgets the viewport
 *     meta tag, leaving phones to render at 980px-wide desktop scale.
 *  2. A new `<td>` in a `.mod-table` is added without `data-label`,
 *     leaving the card-layout cell unlabeled on phone. base.css now has
 *     a `data-col` fallback, but the canonical attribute is still
 *     `data-label`.
 *  3. A page hard-codes a pixel viewport-width container that wouldn't
 *     ever fit on a phone (e.g. `style="width: 1200px"`).
 */
class ResponsiveTemplateContractTest {

    private val templatesDir = File(
        "src/main/resources/templates"
    ).also { dir ->
        assertTrue(dir.isDirectory, "templates dir not found at ${dir.absolutePath}")
    }

    private fun listHtmlTemplates(): List<File> =
        templatesDir.walkTopDown().filter { it.isFile && it.extension == "html" }.toList()

    private fun readResource(path: String): String? =
        javaClass.classLoader.getResource(path)?.readText()

    @Test
    fun `shared head fragments declare the viewport meta tag`() {
        // Both `head(...)` and `headSeo(...)` fragments must carry
        // `<meta name="viewport" ...>` — every user-facing page renders
        // through one of these two fragments. The fragments live in
        // separate files (head.html and headSeo.html) so the HTML5
        // parser doesn't fold two <head> blocks into one and double
        // every <script> tag on every page; each file must still carry
        // its own viewport meta.
        val viewportRe = Regex(
            """<meta\s+name="viewport"\s+content="width=device-width,\s*initial-scale=1""""
        )
        for (path in listOf("templates/fragments/head.html", "templates/fragments/headSeo.html")) {
            val src = readResource(path)
            assertNotNull(src, "$path must exist on the classpath")
            val matches = viewportRe.findAll(src!!).count()
            assertEquals(
                1, matches,
                "$path must declare exactly one viewport meta. Found $matches. Without it " +
                    "iPhones render the dashboard at 980px-wide desktop scale and the responsive " +
                    "CSS never kicks in."
            )
        }
    }

    @Test
    fun `every td under a mod-table thymeleaf row carries a data-label`() {
        // Iterates every template, finds any `<table` with class containing
        // `mod-table` (or `lb-standings-table` which extends it), and
        // asserts every `<td` inside the table has a `data-label`. Catches
        // the regression where a new column is added to the moderation
        // users table without the attribute and renders unlabeled on phone.
        val violations = mutableListOf<String>()
        for (file in listHtmlTemplates()) {
            val src = file.readText()
            // Locate every table opening tag that mentions `mod-table` in
            // its class attribute.
            val tableOpenRe = Regex("""<table\b[^>]*class="[^"]*\bmod-table\b[^"]*"[^>]*>""")
            for (open in tableOpenRe.findAll(src)) {
                val startIdx = open.range.last + 1
                val endIdx = src.indexOf("</table>", startIdx).let {
                    if (it < 0) src.length else it
                }
                val tableBody = src.substring(startIdx, endIdx)
                // Find every `<td ...>` and verify it has a data-label
                // attribute somewhere in the opening tag. Thymeleaf
                // expressions inside the attribute value are fine — only
                // presence is checked. `colspan`-wide cells (typically
                // empty-state placeholders spanning every column) don't
                // map to a single column, so skip them.
                val tdRe = Regex("""<td\b([^>]*)>""")
                for (td in tdRe.findAll(tableBody)) {
                    val attrs = td.groupValues[1]
                    if (attrs.contains("colspan")) continue
                    if (!attrs.contains("data-label")) {
                        val line = src.substring(0, startIdx + td.range.first)
                            .count { it == '\n' } + 1
                        violations += "${file.relativeTo(templatesDir).path}:$line — <td${attrs.take(60)}…"
                    }
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Every <td> inside a .mod-table must declare `data-label=\"...\"` so the mobile " +
                "card transform (base.css @media max-width: 600px) renders a heading next to the " +
                "value. base.css has a data-col fallback for emergencies, but data-label is the " +
                "canonical attribute. Violations:\n" + violations.joinToString("\n")
        )
    }

    @Test
    fun `no template hard-codes a desktop-only inline pixel width`() {
        // Inline `style="width: NNNpx"` (or min-width) with a value bigger
        // than the smallest target viewport (320px) is almost always a
        // mistake — it'll force horizontal scroll on every phone. SVG
        // attributes (width="160" on a fixed graphic) are fine and not
        // matched here.
        val violations = mutableListOf<String>()
        val inlineWidthRe = Regex(
            """style="[^"]*\b(?:min-)?width:\s*([0-9]{3,4})px"""
        )
        for (file in listHtmlTemplates()) {
            val src = file.readText()
            for (m in inlineWidthRe.findAll(src)) {
                val px = m.groupValues[1].toInt()
                if (px > 320) {
                    val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                    violations += "${file.relativeTo(templatesDir).path}:$line — ${m.value}"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Templates must not embed inline `style=\"width: NNNpx\"` greater than 320px — " +
                "those are exactly the kind of fixed widths that defeat the responsive CSS " +
                "and force horizontal scroll on phones. Use a class with a CSS max-width or " +
                "a responsive `width: 100%` instead.\n" + violations.joinToString("\n")
        )
    }
}
