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

    @Test
    fun `jackpot banner reads the live jackpotStakeAnchor model attribute`() {
        // The win-roll scales by `stake / JACKPOT_STAKE_ANCHOR` (see
        // JackpotHelper.rollOnWin), not by each game's max stake. The
        // banner used to claim "scaled by your stake ÷ that game's max"
        // which was simply false — admins could raise per-game caps
        // without the divisor moving. Lock in that the fragment now
        // reads the live anchor so the displayed threshold matches the
        // actual scaling formula.
        assertTrue(
            fragmentHtml.contains("jackpotStakeAnchor"),
            "expected the fragment to reference the jackpotStakeAnchor model attribute"
        )
    }

    @Test
    fun `jackpot banner no longer claims scaling is by that game's max`() {
        // Guard against the misleading wording resurfacing — the divisor
        // is the per-guild stake anchor, not the per-game max stake.
        assertFalse(
            fragmentHtml.contains("game's max"),
            "fragment should not claim scaling is by 'that game's max' — divisor is JACKPOT_STAKE_ANCHOR"
        )
    }

    @Test
    fun `jackpot banner gates the eligible explanation behind jackpotIneligible`() {
        // When the per-guild RTP ceiling marks this game as ineligible to
        // roll, the banner must not still claim "win any minigame for X%
        // chance" — that's misleading. The eligible blurb is rendered
        // with th:unless so it's hidden whenever the model marks the
        // game ineligible.
        assertTrue(
            fragmentHtml.contains("th:unless=\"\${jackpotIneligible}\""),
            "fragment must hide the eligible-by-default blurb when jackpotIneligible is true"
        )
    }

    @Test
    fun `jackpot banner shows an ineligibility explanation when gated out by per-guild RTP`() {
        // Symmetric branch — the banner explains *why* the game won't
        // roll (RTP above the configured ceiling) instead of going
        // silent. Includes the configured ceiling so the user can map
        // it to the admin's setting if they ask.
        assertTrue(
            fragmentHtml.contains("jackpotIneligibleReason == 'rtp'"),
            "fragment must guard the RTP-reason blurb on jackpotIneligibleReason"
        )
        assertTrue(
            fragmentHtml.contains("jackpotRtpMax"),
            "RTP-reason blurb must surface the configured RTP ceiling"
        )
    }

    @Test
    fun `jackpot banner does not use SpEL 'and' on the nullable jackpotIneligible attribute`() {
        // Regression guard for the PageRenderSmokeIT failure on PR #440:
        // `th:if="${jackpotIneligible and jackpotIneligibleReason == 'rtp'}"`
        // throws SpelEvaluationException on every eligible-game page because
        // `jackpotIneligible` is null there and SpEL's `and` does not
        // short-circuit on null. The reason-branches must be guarded by a
        // nested `th:block th:if="${jackpotIneligible}"` so they only
        // evaluate when the attribute is truthy. Substring guard is tight:
        // any future "${jackpotIneligible and ..." is the exact bug.
        assertFalse(
            fragmentHtml.contains("\${jackpotIneligible and"),
            "fragment must not chain SpEL 'and' on the nullable jackpotIneligible — wrap inner branches in <th:block th:if=\"\${jackpotIneligible}\"> instead"
        )
    }

    @Test
    fun `jackpot banner shows a structural-reason explanation for hard-coded carve-outs`() {
        // HighLow's RTP is honest but its win rate is so high that
        // `rollOnWin` would farm rolls; `JackpotGame.eligibleForJackpot=false`
        // disables the win-roll regardless of per-guild config. The banner
        // explains this with a separate, RTP-free message so the player
        // doesn't think the admin configured a ceiling that bites HighLow.
        assertTrue(
            fragmentHtml.contains("jackpotIneligibleReason == 'structural'"),
            "fragment must guard the structural-reason blurb on jackpotIneligibleReason"
        )
        assertTrue(
            fragmentHtml.contains("win rate is too"),
            "structural-reason blurb must explain it's win-rate driven, not RTP driven"
        )
    }

    @Test
    fun `jackpot banner exposes anti-cheat tooltip via title attribute`() {
        // Hover-discoverable note about the bot-suspicion / forced-loss
        // system (see CasinoBotSuspicionService + CasinoEdgeService.applyBotEdge).
        // Surfacing it on the banner means a user who's losing to the
        // anti-cheat layer can find out why without trawling through
        // server-only code paths.
        assertTrue(
            fragmentHtml.contains("title="),
            "fragment should expose a title attribute for the anti-cheat tooltip"
        )
        assertTrue(
            fragmentHtml.contains("Anti-cheat"),
            "fragment tooltip should mention anti-cheat so the warning is discoverable"
        )
    }
}
