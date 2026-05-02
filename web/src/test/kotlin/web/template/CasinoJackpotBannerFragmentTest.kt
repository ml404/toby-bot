package web.template

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presence regression for the casino jackpot banner. The fragment used
 * to hardcode "1% chance to bank the pool" even though the live win
 * probability comes from the admin-configurable `JACKPOT_WIN_PCT` —
 * setting it to 5 still showed "1%" on every casino page. The fragment
 * now reads the [jackpotWinPct] model attribute and formats it via
 * `#numbers.formatDecimal`. This test reads the fragment off the
 * classpath and asserts the binding is in place and the literal `1%`
 * string is gone, so a future template tidy-up can't silently revert.
 */
class CasinoJackpotBannerFragmentTest {

    private val fragmentHtml: String by lazy {
        val url = javaClass.classLoader.getResource("templates/fragments/casino.html")
        assertNotNull(url, "templates/fragments/casino.html must be on the classpath")
        url!!.readText()
    }

    @Test
    fun `jackpot banner reads the live jackpotWinPct model attribute`() {
        assertTrue(
            fragmentHtml.contains("jackpotWinPct"),
            "expected the fragment to reference the jackpotWinPct model attribute"
        )
        assertTrue(
            fragmentHtml.contains("formatDecimal"),
            "expected the fragment to format jackpotWinPct via #numbers.formatDecimal"
        )
    }

    @Test
    fun `jackpot banner no longer hardcodes the 1 percent chance string`() {
        // Specifically guard against the old wording resurfacing — any
        // future copy edit must keep the binding instead of inlining a
        // number, otherwise a guild that has tuned JACKPOT_WIN_PCT will
        // see a misleading banner again.
        assertFalse(
            fragmentHtml.contains("1% chance"),
            "fragment should not hardcode '1% chance' — render jackpotWinPct instead"
        )
    }
}
