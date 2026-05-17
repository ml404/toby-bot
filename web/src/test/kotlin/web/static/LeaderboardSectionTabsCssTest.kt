package web.static

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the section-tab redesign contract in `leaderboard.css`.
 *
 * The tabs originally cloned the `.lb-sort` pill aesthetic — a small,
 * left-aligned `inline-flex` strip designed for an inline switcher. As a
 * top-level section nav between the podium card row and the table panels,
 * that read as underweight. This test guards against three regressions
 * back toward that look:
 *
 * 1. **The tabs go back to `inline-flex`.** The redesign uses
 *    `display: flex` with `width: 100%` so the segments span the row.
 *    A "tidy CSS" pass that swaps it back would re-introduce the
 *    visually-lost look from the original screenshot.
 *
 * 2. **An equal-width invariant drops.** `flex: 1 1 0` on each
 *    `.lb-sort-option` keeps the three segments balanced even when the
 *    TobyCoin tab is conditionally hidden by Thymeleaf. Drop the rule
 *    and the remaining two cards skew based on content width.
 *
 * 3. **The label/count classes disappear.** The template structures
 *    each tab as `.lb-section-tab-label` over `.lb-section-tab-count`,
 *    so removing either class from the stylesheet leaves the count
 *    badge unstyled (or worse, visually identical to the label).
 *
 * 4. **The phone shrink is dropped.** The 480px media query tightens
 *    padding + font sizes so the three short labels fit at the canonical
 *    --bp-tiny width. A refactor that removes it would push the cards
 *    past the viewport on iPhone SE / Pixel 5.
 */
class LeaderboardSectionTabsCssTest {

    private val leaderboardCss: String by lazy { readCss("static/css/leaderboard.css") }

    private fun readCss(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("$path is not on the classpath")

    private fun ruleBody(css: String, selector: String): String {
        // Find the rule `selector { ... }` and return its body.
        val needle = "$selector {"
        val start = css.indexOf(needle)
        check(start >= 0) { "Selector `$selector` not found in CSS" }
        val bodyStart = start + needle.length
        val bodyEnd = css.indexOf('}', bodyStart)
        check(bodyEnd >= 0) { "Unterminated rule body for `$selector`" }
        return css.substring(bodyStart, bodyEnd)
    }

    @Test
    fun `lb-section-tabs uses flex (not inline-flex) and spans full width`() {
        val body = ruleBody(leaderboardCss, ".lb-section-tabs")
        assertTrue(
            body.contains(Regex("""\bdisplay:\s*flex\b""")),
            "`.lb-section-tabs` must use `display: flex` so the segments span the row " +
                "as a top-level section nav. `inline-flex` reads as a small pill strip and " +
                "drove the original 'visually lost' redesign."
        )
        assertFalse(
            body.contains(Regex("""\bdisplay:\s*inline-flex\b""")),
            "`.lb-section-tabs` must not regress to `inline-flex`."
        )
        assertTrue(
            body.contains(Regex("""\bwidth:\s*100%""")),
            "`.lb-section-tabs` must declare `width: 100%` so the row stretches across " +
                "the container instead of hugging its content."
        )
    }

    @Test
    fun `each tab uses flex 1 1 0 so widths stay balanced when TobyCoin is hidden`() {
        val body = ruleBody(leaderboardCss, ".lb-section-tabs .lb-sort-option")
        assertTrue(
            body.contains(Regex("""\bflex:\s*1\s+1\s+0""")),
            "`.lb-section-tabs .lb-sort-option` must declare `flex: 1 1 0` so the " +
                "remaining cards equalise when the TobyCoin tab is conditionally hidden " +
                "via Thymeleaf `th:if`. Drop the rule and the layout skews based on " +
                "content width."
        )
    }

    @Test
    fun `tab card uses the elevated-card aesthetic, not the bg-input pill`() {
        val body = ruleBody(leaderboardCss, ".lb-section-tabs .lb-sort-option")
        assertTrue(
            body.contains("var(--bg-elevated)"),
            "tab cards should use the same elevated background as `.lb-stat` and " +
                "`.lb-podium-card` so the row reads as a card cluster, not a pill strip."
        )
        assertTrue(
            body.contains("var(--radius-lg)"),
            "tab cards should use `--radius-lg` (matching .lb-stat / .lb-podium-card)."
        )
    }

    @Test
    fun `active tab inverts to the accent fill`() {
        val body = ruleBody(leaderboardCss, ".lb-section-tabs .lb-sort-option.active")
        assertTrue(
            body.contains(Regex("""background:\s*var\(--accent\)""")),
            "the active tab must invert to the accent fill so the selection is " +
                "obvious at a glance — this is the headline visual difference vs. the " +
                "old pill design."
        )
    }

    @Test
    fun `label and count classes are both declared so the template can rely on them`() {
        assertTrue(
            leaderboardCss.contains(Regex("""\.lb-section-tab-label\s*\{""")),
            "`.lb-section-tab-label` must be declared — the template structures each " +
                "tab as `<span class=\"lb-section-tab-label\">…</span>` over a count badge."
        )
        assertTrue(
            leaderboardCss.contains(Regex("""\.lb-section-tab-count\s*\{""")),
            "`.lb-section-tab-count` must be declared so the count badge has its own " +
                "size + colour tokens (smaller + muted) instead of inheriting the label's."
        )
    }

    @Test
    fun `count badge becomes legible-on-accent when its parent tab is active`() {
        // The default count colour is `--text-muted`, which is barely visible
        // against the accent fill. The redesign overrides it to a translucent
        // white when the parent tab is active. Without this rule the count
        // disappears on the selected tab.
        assertTrue(
            leaderboardCss.contains(
                Regex("""\.lb-section-tabs\s+\.lb-sort-option\.active\s+\.lb-section-tab-count\s*\{[^}]*color:""")
            ),
            "active-tab styling must override `.lb-section-tab-count` colour so the " +
                "badge stays legible against the accent fill."
        )
    }

    @Test
    fun `phone-shrink media query targets the section tabs at 480px`() {
        // The canonical small-phone breakpoint per `responsive.test.js` is
        // 480px. The redesign tightens padding + fonts here so three labels
        // fit at --bp-tiny width without horizontal overflow.
        val match = Regex(
            """@media\s*\(max-width:\s*480px\)\s*\{[^}]*\.lb-section-tabs""",
            RegexOption.DOT_MATCHES_ALL
        ).containsMatchIn(leaderboardCss)
        assertTrue(
            match,
            "leaderboard.css must declare a `@media (max-width: 480px)` block that " +
                "targets `.lb-section-tabs` (or a nested selector). The phone shrink " +
                "is what keeps three cards readable at 380–390px."
        )
    }
}
