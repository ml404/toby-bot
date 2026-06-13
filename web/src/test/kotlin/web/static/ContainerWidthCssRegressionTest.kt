package web.static

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the `width: 100%` on `.container` / `.container-wide` in base.css.
 *
 * `body` is a column flexbox (the sticky-footer mechanism), which makes
 * these layout containers flex items. A flex item sized `width: auto` is
 * sized by its content's min-content width, so a single non-wrapping child
 * wider than the viewport — most visibly the moderation `.mod-subnav`
 * ten-tab horizontal-scroll strip — stretches the container past 100vw and
 * throws every moderation/admin page into horizontal scroll on phones.
 *
 * Pinning `width: 100%` (clamped by the existing `max-width`) gives the
 * flex item a definite cross size so it tracks the viewport instead of its
 * content. Dropping the declaration silently reintroduces the phone
 * overflow, which only the Playwright responsive suite would otherwise
 * catch — this unit test fails fast in the JVM build.
 */
class ContainerWidthCssRegressionTest {

    private val css: String by lazy {
        javaClass.classLoader
            .getResourceAsStream("static/css/base.css")
            ?.bufferedReader()
            ?.readText()
            ?: error("base.css is not on the classpath")
    }

    @Test
    fun `container rules pin width to 100 percent so the column-flex body cannot shrink-wrap them`() {
        // Collapse whitespace so the assertion is resilient to reformatting
        // of the individual declarations within each rule.
        val flattened = css.replace(Regex("\\s+"), " ")
        listOf(".container", ".container-wide").forEach { selector ->
            val rule = Regex(Regex.escape("$selector {") + "[^}]*}")
                .find(flattened)?.value
                ?: error("base.css must declare a `$selector { ... }` rule")
            assertTrue(
                rule.contains("width: 100%"),
                "$selector must set `width: 100%` so it cannot shrink-wrap to an " +
                    "oversized child as a flex item of the column-flex body. Rule was: $rule",
            )
        }
    }
}
