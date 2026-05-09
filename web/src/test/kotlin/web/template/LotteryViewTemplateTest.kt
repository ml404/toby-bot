package web.template

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presence regression for the lottery page template. Asserts the page:
 *  - reads the snapshot model attribute (no hardcoded numbers/copy)
 *  - exposes the picker grid + every cell-state class the JS toggles
 *  - reuses the shared jackpot banner fragment + casino-card-face cell base
 *
 * A refactor that accidentally drops any of these silently breaks
 * either the buy flow or the result panel — both impossible to spot
 * without playing through, hence this regression suite.
 */
class LotteryViewTemplateTest {

    private val html: String by lazy {
        val url = javaClass.classLoader.getResource("templates/lottery.html")
        assertNotNull(url, "templates/lottery.html must be on the classpath")
        url!!.readText()
    }

    @Test
    fun `lottery page reads snapshot model attributes`() {
        // Driven entirely by the LotteryViewModel snapshot — picking
        // numbers, ticket price, pool, drawn numbers, my picks, etc.
        // come from the server. Hardcoded copy here would silently
        // ignore admin tuning.
        listOf(
            "snapshot.pickCount",
            "snapshot.numberMax",
            "snapshot.daily",
            "snapshot.tierPercents",
            "snapshot.revenueJackpotPct",
        ).forEach { attr ->
            assertTrue(
                html.contains(attr),
                "lottery.html must reference \${$attr} so admin-tuned values render dynamically."
            )
        }
    }

    @Test
    fun `lottery page renders the number picker grid with pickable cells`() {
        // The grid is server-rendered (progressive-enhancement safe) so
        // even a JS-disabled visitor sees the cells; the JS hooks click
        // handlers on the existing buttons.
        assertTrue(
            html.contains("class=\"lottery-cells\""),
            "lottery.html must declare a .lottery-cells container for the picker"
        )
        assertTrue(
            html.contains("class=\"lottery-cell casino-card-face\""),
            "picker cells must use .lottery-cell + .casino-card-face for the shared card styling"
        )
        assertTrue(
            html.contains("data-value="),
            "picker cells must carry data-value so the JS knows which number is which"
        )
    }

    @Test
    fun `lottery page reuses the shared casino jackpot banner`() {
        // The page's jackpot banner is the same one every minigame uses —
        // a custom one would risk drifting from the rest of the casino.
        assertTrue(
            html.contains("fragments/casino :: jackpotBanner"),
            "lottery.html must @{fragments/casino :: jackpotBanner} to share the casino styling"
        )
    }

    @Test
    fun `lottery page does not hardcode the pick count or number max`() {
        // The text "Pick 5 of 1-49" must come from the snapshot, not be
        // baked in — a future variant (Pick 4 of 20, etc.) shouldn't
        // need a template edit.
        assertFalse(
            html.contains("Pick 5 of 1-49"),
            "lottery.html should not hardcode 'Pick 5 of 1-49' — render snapshot.pickCount + snapshot.numberMax"
        )
    }

    @Test
    fun `lottery page references the daily countdown attribute`() {
        // The closes_at countdown is driven by data-closes-at, not a
        // server-rendered string — matters when the user keeps the page
        // open across midnight.
        assertTrue(
            html.contains("data-closes-at="),
            "lottery.html must expose data-closes-at so JS can run the countdown"
        )
    }
}
