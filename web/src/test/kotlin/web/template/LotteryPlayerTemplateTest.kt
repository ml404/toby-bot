package web.template

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presence regression for the player-facing lottery template. Pins the
 * incentives panel that surfaces participation tiers + personalised
 * next-threshold hints + milestone progress on `/casino/{id}/lottery`.
 *
 * Asserts on raw HTML so a typo'd Thymeleaf expression (e.g. renaming
 * `snapshot.weighted.incentives` without updating the template) fails
 * CI rather than silently rendering an empty panel. The view-model
 * derivation itself is covered by `LotteryControllerTest`.
 */
class LotteryPlayerTemplateTest {

    private val lotteryHtml: String by lazy {
        val url = javaClass.classLoader.getResource("templates/lottery.html")
        assertNotNull(url, "templates/lottery.html must be on the classpath")
        url!!.readText()
    }

    @Test
    fun `player lottery template carries a Participation incentives section gated on incentives non-null`() {
        // Only TICKET_WEIGHTED draws get the panel — the
        // `snapshot.weighted != null and snapshot.weighted.incentives != null`
        // guard hides it for NUMBER_MATCH (where incentives are inert)
        // and for empty config (where the panel would be visually
        // empty). Two render sites: the WEIGHTED-daily section and
        // the admin-fired Featured event card.
        assertTrue(
            lotteryHtml.contains("<h3>Participation incentives</h3>"),
            "expected the Participation incentives heading on the weighted section",
        )
        // The Thymeleaf guard must reference the incentives field on
        // the weighted view model — if a refactor renames it, the
        // assertion lights up immediately.
        assertTrue(
            lotteryHtml.contains("snapshot.weighted.incentives != null"),
            "expected the panel to be gated on snapshot.weighted.incentives != null",
        )
    }

    @Test
    fun `player lottery template renders all three lever subsections with data-incentive markers`() {
        // Each lever block carries a `data-incentive` attribute so the
        // test (and any future JS) can scope to it without depending on
        // emoji order or copy. Losing any one of these means a whole
        // lever silently fell off the page.
        for (lever in listOf("bulk", "multiplier", "milestone")) {
            assertTrue(
                lotteryHtml.contains("data-incentive=\"$lever\""),
                "expected a `data-incentive=\"$lever\"` block in the player lottery template",
            )
        }
    }

    @Test
    fun `player lottery template renders the next-threshold hint expressions per lever`() {
        // The hint copy is computed view-side as `nextBulkHint /
        // nextMultiplierHint`. Pin both expressions so a hint-block
        // refactor doesn't accidentally drop one side and leave a
        // half-rendered panel in prod.
        for (hint in listOf(
            "inc.nextBulkHint",
            "inc.nextMultiplierHint",
        )) {
            assertTrue(
                lotteryHtml.contains(hint),
                "expected $hint reference in the player lottery template",
            )
        }
    }

    @Test
    fun `player lottery template renders a progress bar for the next-to-fire milestone`() {
        // The `<progress>` element drives the live FOMO bar; the
        // `inc.milestoneProgress` view-model field carries
        // current / threshold / pct. Verify both the element and the
        // expression so a refactor that renames either fails fast.
        assertTrue(
            lotteryHtml.contains("inc.milestoneProgress"),
            "expected inc.milestoneProgress reference for the milestone progress bar",
        )
        assertTrue(
            lotteryHtml.contains("<progress"),
            "expected a <progress> element to render the milestone progress bar",
        )
    }
}
