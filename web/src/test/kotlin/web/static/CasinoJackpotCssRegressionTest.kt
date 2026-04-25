package web.static

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The jackpot banner and per-game `*-result-jackpot` accent live in
 * `base.css` so every minigame page (slots / coinflip / dice / highlow /
 * scratch) renders them without duplicating styles. If a future cleanup
 * relocates these to a per-game stylesheet without porting them all, the
 * banner reverts to default text styling and the jackpot win line stops
 * looking distinct — both regressions players have explicitly asked us
 * to keep visible.
 */
class CasinoJackpotCssRegressionTest {

    private val css: String by lazy {
        javaClass.classLoader
            .getResourceAsStream("static/css/base.css")
            ?.bufferedReader()
            ?.readText()
            ?: error("base.css is not on the classpath")
    }

    @Test
    fun `base css declares the casino-jackpot-banner class`() {
        assertTrue(
            css.contains(".casino-jackpot-banner"),
            "base.css must define .casino-jackpot-banner so every minigame template's pool render is styled."
        )
    }

    @Test
    fun `base css declares the per-game result-jackpot accents`() {
        listOf(
            ".slots-result-jackpot",
            ".coinflip-result-jackpot",
            ".dice-result-jackpot",
            ".highlow-result-jackpot",
            ".scratch-result-jackpot"
        ).forEach { selector ->
            assertTrue(
                css.contains(selector),
                "base.css must include $selector so JS-attached jackpot wins render with the shared accent."
            )
        }
    }
}
