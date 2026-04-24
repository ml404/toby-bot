package web.static

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The chart overlay in `economy.html` is hidden/shown by toggling the HTML
 * `hidden` attribute from `economy.js`. Because `.economy-chart-empty`
 * sets `display: flex`, the UA-defined `[hidden] { display: none }` rule
 * is overridden by class-selector specificity — which is why the overlay
 * used to stay visible on top of a rendered chart. The CSS file must
 * carry an explicit `.economy-chart-empty[hidden] { display: none }` rule.
 */
class EconomyCssRegressionTest {

    @Test
    fun `economy css hides the chart-empty overlay when the hidden attribute is set`() {
        val css = javaClass.classLoader
            .getResourceAsStream("static/css/economy.css")
            ?.bufferedReader()
            ?.readText()
            ?: error("economy.css is not on the classpath")

        val normalized = css.replace("\\s+".toRegex(), " ")
        assertTrue(
            normalized.contains(".economy-chart-empty[hidden]") &&
                normalized.substringAfter(".economy-chart-empty[hidden]")
                    .substringBefore("}")
                    .contains("display: none"),
            "economy.css must include `.economy-chart-empty[hidden] { display: none; }` " +
                "so toggling the `hidden` attribute from JS actually hides the overlay."
        )
    }
}
