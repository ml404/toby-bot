package web.template

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presence regression for the casino jackpot banner. The fragment used
 * to hardcode "1% chance to bank the pool" even though the live win
 * probability comes from the admin-configurable `JACKPOT_WIN_PCT`. It
 * also used `#numbers.formatDecimal(jackpotWinPct, 1, 2)` which forced
 * exactly 2 decimal places, so neighbouring values like `0.005` and
 * `0.0005` collapsed to the same `"0.01"` string. The fragment now
 * renders the precomputed `JackpotService.winProbabilityDisplay` string
 * directly so trailing zeros are stripped and small decimals survive.
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
    }

    @Test
    fun `jackpot banner renders the precomputed string without forcing 2 decimal places`() {
        // formatDecimal(value, 1, 2) rounded sub-1% values like 0.0005
        // back up to "0.01", masking the configured precision. The
        // server-side `winProbabilityDisplay` already trims to a sensible
        // string, so the fragment must just render it.
        assertFalse(
            fragmentHtml.contains("formatDecimal"),
            "fragment should render jackpotWinPct directly — not via formatDecimal"
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
