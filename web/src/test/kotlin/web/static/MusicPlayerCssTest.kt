package web.static

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the music-player polish contract so the dashboard keeps pace with
 * the rest of the web surfaces. Three concrete regressions this guards:
 *
 * 1. **`.btn-tertiary` loses its styling.** The "← All servers" back link,
 *    the playlist Load button and the search Clear button all use
 *    `.btn-tertiary`. That class was referenced across the music templates
 *    but only ever had a touch min-height rule — so the buttons rendered
 *    as raw browser-default chrome. The real ruleset now lives in
 *    `base.css` (with the rest of the button family); if it regresses to a
 *    bare touch rule the buttons go back to looking unstyled.
 *
 * 2. **The music sheet drifts back off the design tokens.** It used to
 *    reference a non-existent `--muted` custom property (which silently
 *    fell back to a hardcoded grey) instead of the real `--text-muted`
 *    token. This test fails if `--muted` reappears.
 *
 * 3. **The now-playing flourish is dropped.** The hero card lights up
 *    (`.is-playing`) with an accent glow + a CSS equalizer while audio is
 *    playing, and the equalizer animation must be killed under
 *    prefers-reduced-motion.
 */
class MusicPlayerCssTest {

    private val baseCss: String by lazy { readCss("static/css/base.css") }
    private val musicCss: String by lazy { readCss("static/css/music-player.css") }

    private fun readCss(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("$path is not on the classpath")

    @Test
    fun `btn-tertiary has a real ruleset in base css`() {
        // The standalone declaration block (not the touch-block min-height
        // one-liner) must set the visual properties that make it look like
        // a button: background, colour and border.
        val block = topLevelRuleBody(baseCss, ".btn-tertiary")
            ?: error("base.css must declare a top-level `.btn-tertiary { ... }` rule")
        listOf("background", "color", "border").forEach { prop ->
            assertTrue(
                block.contains("$prop:"),
                "base.css `.btn-tertiary` must set `$prop` so the back-link / " +
                    "Load / Clear buttons render as styled buttons, not raw browser chrome."
            )
        }
    }

    @Test
    fun `btn-tertiary is defined before btn-danger so the danger combo wins`() {
        // The playlist Delete button is `btn-tertiary btn-danger`. Both are
        // single-class selectors (equal specificity), so source order
        // decides the combined look — `.btn-danger` must come AFTER
        // `.btn-tertiary` for the delete button to stay red.
        val tertiaryAt = baseCss.indexOf(".btn-tertiary {")
        val dangerAt = baseCss.indexOf(".btn-danger {")
        assertTrue(
            tertiaryAt in 0 until dangerAt,
            "base.css must define `.btn-tertiary` before `.btn-danger` so the " +
                "`.btn-tertiary.btn-danger` Delete button keeps the danger palette."
        )
    }

    @Test
    fun `music css uses design tokens, not the phantom --muted property`() {
        assertFalse(
            musicCss.contains("--muted"),
            "music-player.css must use the real `--text-muted` token — `--muted` " +
                "is not defined in base.css and silently falls back to a hardcoded grey."
        )
        assertTrue(
            musicCss.contains("var(--text-muted)"),
            "music-player.css should reference the shared `--text-muted` token."
        )
    }

    @Test
    fun `now-playing hero has the accent flourish`() {
        assertTrue(
            musicCss.contains(".now-playing-card.is-playing"),
            "music-player.css must light up the now-playing card via `.is-playing`."
        )
        assertTrue(
            musicCss.contains(".now-playing-eq") && musicCss.contains("@keyframes eq-bounce"),
            "music-player.css must define the now-playing equalizer + its keyframes."
        )
    }

    @Test
    fun `equalizer animation is disabled under reduced motion`() {
        assertTrue(
            musicCss.contains("prefers-reduced-motion: reduce"),
            "music-player.css must respect prefers-reduced-motion."
        )
        // Collapse whitespace so the assertion isn't sensitive to formatting,
        // then require the equalizer bars to have their animation cancelled.
        val normalized = musicCss.replace("\\s+".toRegex(), " ")
        assertTrue(
            normalized.contains(".now-playing-eq span { animation: none"),
            "music-player.css must set `animation: none` on the equalizer bars " +
                "under prefers-reduced-motion so motion-sensitive users see static bars."
        )
    }

    /**
     * Returns the body of the first top-level `selector { ... }` rule, or
     * null. "Top-level" excludes occurrences nested inside an `@media`
     * block (those open with the selector after a `{` that is itself inside
     * the media query) — but for `.btn-tertiary` the standalone rule is at
     * column 0, while the touch-block reference is indented inside a media
     * query, so a simple `\nselector {` anchor distinguishes them.
     */
    private fun topLevelRuleBody(css: String, selector: String): String? {
        val anchor = "\n$selector {"
        val start = css.indexOf(anchor)
        if (start < 0) return null
        val open = css.indexOf('{', start)
        val close = css.indexOf('}', open)
        if (open < 0 || close < 0) return null
        return css.substring(open + 1, close)
    }
}
