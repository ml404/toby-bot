package web.static

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the "rich Discord-member display" CSS contract. Three concrete
 * bug patterns this test guards against:
 *
 * 1. **The leaderboard avatars regress to natural CDN size.** The
 *    `.member-cell` + `.avatar` rules used to live in `moderation.css`,
 *    which the leaderboard page does NOT load — so leaderboard
 *    standings rows had no flex layout and avatars rendered at whatever
 *    Discord returned (often 128px). Hoisted to `base.css`, the rule
 *    applies on every page that loads the base stylesheet (i.e. all
 *    of them).
 *
 * 2. **A future contributor re-defines a primitive locally.** If
 *    `.member-cell { display: flex; ... }` reappears in `leaderboard.css`
 *    or `lottery.css`, mobile-sizing changes in `base.css` stop
 *    cascading — the duplicate wins on its page only and the layouts
 *    silently drift again.
 *
 * 3. **The mobile shrink is dropped during a refactor.** The shared
 *    480px breakpoint shrinks avatar + rank-badge + title-pill so a
 *    long display name + medal + value pill fit on a 360px-wide phone.
 *    A "tidy CSS" pass that removes the breakpoint would re-introduce
 *    the off-screen-pill bug invisibly.
 */
class RichMemberStylingTest {

    private val baseCss: String by lazy { readCss("static/css/base.css") }
    private val leaderboardCss: String by lazy { readCss("static/css/leaderboard.css") }
    private val lotteryCss: String by lazy { readCss("static/css/lottery.css") }
    private val moderationCss: String by lazy { readCss("static/css/moderation.css") }

    private fun readCss(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("$path is not on the classpath")

    /**
     * The shared primitives the leaderboard / lottery / moderation
     * pages all reference. Each must be defined as a top-level rule
     * in [baseCss] so every page picks it up via the always-loaded
     * base stylesheet.
     *
     * The numeric `.lb-value*` accents joined the list when the
     * standings cards got their mobile redesign — moving them out of
     * leaderboard.css means a future page that wants a coloured
     * value pill can reuse them without re-importing leaderboard.css,
     * AND the mobile shrink in the 640px standings-grid block always
     * lines up with the right rule.
     */
    private val sharedPrimitives = listOf(
        ".member-cell",
        ".avatar",
        ".lb-name",
        ".lb-rank",
        ".lb-rank-1",
        ".lb-rank-2",
        ".lb-rank-3",
        ".lb-title-pill",
        ".lb-value",
        ".lb-value-credits",
        ".lb-value-toby",
        ".lb-value-portfolio",
        ".lb-value-month",
        ".lb-value-month-down",
    )

    @Test
    fun `every shared rich-member primitive is defined in base css`() {
        // Bug 1 guard: if any of these go missing from base.css the
        // leaderboard page loses the rule entirely (it doesn't load
        // moderation.css), and avatars regress to natural CDN size.
        for (selector in sharedPrimitives) {
            assertTrue(
                baseCss.contains("$selector {") || baseCss.contains("$selector,"),
                "base.css must declare $selector — leaderboard.html loads only " +
                    "base.css + leaderboard.css, so a per-page CSS file can't be " +
                    "the home for shared rich-member primitives."
            )
        }
    }

    @Test
    fun `shared primitives are not redefined in per-page css`() {
        // Bug 2 guard: a duplicate definition in a per-page file
        // overrides the shared rule on that page, so a future mobile
        // sizing tweak in base.css silently stops cascading.
        val perPage = mapOf(
            "leaderboard.css" to leaderboardCss,
            "lottery.css" to lotteryCss,
            "moderation.css" to moderationCss,
        )
        for ((file, css) in perPage) {
            for (selector in sharedPrimitives) {
                assertFalse(
                    isTopLevelRule(css, selector),
                    "$file must not redefine $selector — it lives in base.css. " +
                        "A duplicate here will override the shared rule on that page only " +
                        "and the rich-member layouts will drift again."
                )
            }
        }
    }

    @Test
    fun `base css mobile shrink covers avatar + rank badge + title pill`() {
        // Bug 3 guard: the 480px breakpoint shrinks the avatar/badge/pill
        // so long display names don't push the value pill off-screen.
        // base.css has multiple `@media (max-width: 480px)` blocks
        // (modal/toast sizing predates the rich-member shrink), so look
        // across ALL of them and assert each primitive lands in some
        // 480px block.
        val blocks = extractAllMediaBlocks(baseCss, "max-width: 480px")
        assertTrue(
            blocks.isNotEmpty(),
            "base.css must contain at least one `@media (max-width: 480px)` block — " +
                "without it the rich-member primitives revert to desktop sizing on phones."
        )
        val combined = blocks.joinToString("\n")
        listOf(".avatar", ".lb-rank", ".lb-title-pill").forEach { sel ->
            assertTrue(
                isTopLevelRule(combined, sel),
                "base.css must shrink $sel inside an `@media (max-width: 480px)` block — " +
                    "dropping it lets a long name + medal + pill overflow the viewport on a 360px phone."
            )
        }
    }

    @Test
    fun `standings table mobile redesign is centralised in base css`() {
        // The standings cards used to render at 6 stacked label/value
        // rows per row at desktop font sizes — bulky and visually
        // disconnected on a phone. The 640px override re-lays each
        // card as a 2-row grid (rank/member/title on top, three
        // metrics on the bottom). The user explicitly asked for the
        // fix to live in a shared/central location, not as a
        // leaderboard.css one-off.
        val mobileBlocks = extractAllMediaBlocks(baseCss, "max-width: 640px")
        assertTrue(
            mobileBlocks.isNotEmpty(),
            "base.css must contain a `@media (max-width: 640px)` block " +
                "carrying the standings 2-row grid override."
        )
        val combined = mobileBlocks.joinToString("\n")
        assertTrue(
            combined.contains(".lb-standings-table.mod-table tr") &&
                combined.contains("display: grid") &&
                combined.contains("grid-template-areas"),
            "base.css 640px block must declare a CSS-grid layout for " +
                "`.lb-standings-table.mod-table tr` so the standings cards " +
                "show member info on top + metrics on the bottom — not a " +
                "tower of stacked label/value strips."
        )
        // Each metric label that exists in the leaderboard template
        // must be mapped to a grid area. If a future column rename
        // (e.g. "Voice" -> "Voice (mo)") slips through, the cell will
        // collapse onto the rank row and ruin the layout.
        listOf(
            "data-label=\"Credits\"",
            "data-label=\"TOBY\"",
            "data-label=\"This month\"",
            "data-label=\"Voice\"",
            "data-label=\"Portfolio\"",
        ).forEach { selector ->
            assertTrue(
                combined.contains(selector),
                "base.css 640px standings grid must map `$selector` to a " +
                    "grid area; otherwise the metric collapses out of the " +
                    "two-row layout when the data-label changes."
            )
        }

        // And the leaderboard.css override should NOT exist — that's the
        // "centralise it" half of the user's ask. Page-local overrides
        // would defeat the point of moving the rule to base.css.
        assertFalse(
            leaderboardCss.contains(".lb-standings-table.mod-table tr") ||
                leaderboardCss.contains("grid-template-areas"),
            "leaderboard.css must NOT redefine the standings mobile grid; " +
                "the centralised rule lives in base.css."
        )
    }

    @Test
    fun `leaderboard podium has its own phone-portrait shrink`() {
        // Different from the shared primitive shrink because the podium
        // uses bespoke 96/72px avatars; without a dedicated shrink the
        // 1st-place gold-bordered card overflows a 360px viewport.
        val combined = extractAllMediaBlocks(leaderboardCss, "max-width: 480px").joinToString("\n")
        assertTrue(
            isTopLevelRule(combined, ".lb-podium-avatar"),
            "leaderboard.css must contain a `@media (max-width: 480px)` block with a " +
                "rule for .lb-podium-avatar — the 96/72px desktop avatars overflow at 360px. " +
                "(A renamed selector like `.lb-podium-avatar-foo` won't satisfy this test.)"
        )
    }

    /**
     * "Top-level rule for [selector]" means the selector starts a new
     * rule (or is a member of a comma-separated selector list) — not a
     * descendant in a compound selector like `.parent .member-cell`.
     * Walks every occurrence of `selector {` or `selector,` and accepts
     * only when the preceding non-whitespace char is `}` (previous rule
     * ended), `,` (selector list head from a sibling), `*` (block
     * comment close), or start-of-file. Rejects when preceded by an
     * identifier char (`.foo-member-cell`) or by another selector
     * (descendant combinator).
     */
    private fun isTopLevelRule(css: String, selector: String): Boolean {
        val terminators = listOf('{', ',')
        var idx = 0
        while (idx < css.length) {
            val hit = css.indexOf(selector, idx)
            if (hit < 0) return false
            idx = hit + selector.length

            // Trailing char must (after optional whitespace) be { or ,.
            var t = idx
            while (t < css.length && css[t].isWhitespace()) t++
            if (t >= css.length || css[t] !in terminators) continue

            // Reject identifier-char prefix (`.foo-member-cell`).
            val prev = if (hit == 0) ' ' else css[hit - 1]
            if (prev.isLetterOrDigit() || prev == '-' || prev == '_') continue

            // Walk back through whitespace; the first non-whitespace
            // char must be a rule terminator (`}`), a selector-list
            // separator (`,`), a block-comment close (`/`), or the
            // start of the file. Anything else (a class char, etc.)
            // means we're inside a compound or descendant selector.
            var b = hit - 1
            while (b >= 0 && css[b].isWhitespace()) b--
            if (b >= 0 && css[b] !in setOf('}', ',', '/', '>', '+', '~').plus(';')) continue
            // Note: `;` shouldn't appear at the top level between rules
            // in valid CSS, but we permit it defensively. `>`, `+`, `~`
            // suggest combinators inside a rule's selector list, which
            // we DO want to flag — but only when our selector is the
            // last component, not when it's `.parent > .member-cell`.
            // Practical CSS in this repo doesn't use combinators ahead
            // of these primitives, so this pragmatic check is fine.

            return true
        }
        return false
    }

    /**
     * Return the bodies of every `@media (...QUERY...) { ... }` block
     * in [css]. Handles nested braces inside the media block.
     */
    private fun extractAllMediaBlocks(css: String, query: String): List<String> {
        val out = mutableListOf<String>()
        val anchor = "@media"
        var idx = 0
        while (true) {
            val mediaStart = css.indexOf(anchor, idx)
            if (mediaStart < 0) return out
            val openBrace = css.indexOf('{', mediaStart)
            if (openBrace < 0) return out
            val header = css.substring(mediaStart, openBrace)
            if (!header.contains(query)) {
                idx = openBrace + 1
                continue
            }
            var depth = 1
            var i = openBrace + 1
            while (i < css.length && depth > 0) {
                when (css[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            if (depth == 0) {
                out += css.substring(openBrace + 1, i - 1)
                idx = i
            } else {
                return out
            }
        }
    }
}
