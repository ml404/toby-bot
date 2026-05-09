package web.static

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presence regression for the daily lottery page styling. The picker
 * grid relies on `.lottery-cell` + state classes (`is-picked`,
 * `is-drawn`, `is-matched`) to communicate "you selected this", "this
 * was drawn", and "you matched this drawn number". A future refactor
 * that drops any of these would silently regress the visual feedback —
 * the picker still works but stops glowing on matches, which is the
 * whole point.
 */
class LotteryCssRegressionTest {

    private val css: String by lazy {
        javaClass.classLoader
            .getResourceAsStream("static/css/lottery.css")
            ?.bufferedReader()
            ?.readText()
            ?: error("lottery.css is not on the classpath")
    }

    @Test
    fun `lottery css declares the cell base + state classes`() {
        listOf(
            ".lottery-cell",
            ".lottery-cell.is-picked",
            ".lottery-cell.is-drawn",
            ".lottery-cell.is-matched",
            ".lottery-cells",
            ".lottery-result-win",
            ".lottery-result-lose",
        ).forEach { selector ->
            assertTrue(
                css.contains(selector),
                "lottery.css must declare $selector so the picker UI's state-driven feedback survives refactors."
            )
        }
    }

    @Test
    fun `lottery css imports the shared casino-table tokens`() {
        // Without this import the page loses the shared --casino-gold,
        // --casino-active-glow, etc. tokens and the picker stops looking
        // like the rest of the casino games.
        assertTrue(
            css.contains("casino-table.css"),
            "lottery.css must @import casino-table.css for the shared casino design tokens."
        )
    }
}
