package web.template

import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `player lottery template renders the multiplier next-threshold hint expression`() {
        // The multiplier lever is correctly cumulative — "hold X more
        // tickets to reach 1.25×" — so a personalised hint there is
        // meaningful. Pin the expression so a future hint-block
        // refactor doesn't silently lose it.
        assertTrue(
            lotteryHtml.contains("inc.nextMultiplierHint"),
            "expected inc.nextMultiplierHint reference in the player lottery template",
        )
    }

    @Test
    fun `player lottery template has no bulk-tier personalised hint expression`() {
        // Bulk bonus is per-purchase, not cumulative. A previous
        // implementation rendered "Buy N more in one purchase to earn
        // +B free" with N = tier.buy - myTickets, which was wrong:
        // existing holdings don't shrink the threshold. The active-
        // rules list above already conveys what each tier requires;
        // no personalised hint should appear anywhere in the template.
        assertFalse(
            lotteryHtml.contains("nextBulkHint"),
            "bulk-tier personalised hint was removed because the gap math is " +
                "incorrect for a per-purchase reward; do not re-introduce " +
                "without changing the semantics first",
        )
    }

    @Test
    fun `player lottery template renders 'paid + bonus' breakdown on top holders when h_bonusTickets is non-zero`() {
        // Each top-holder row's ticket pill must match the "Your
        // tickets" treatment: when the holder has bonus tickets, show
        // "X paid + Y bonus", else just the paid count. Pin the
        // expression in both render sites (WEIGHTED daily + Featured
        // event card) — the conditional appears twice in the template.
        assertTrue(
            lotteryHtml.contains("h.bonusTickets > 0"),
            "expected the top-holders pill to branch on h.bonusTickets > 0",
        )
        val occurrences = "h.bonusTickets > 0".toRegex().findAll(lotteryHtml).count()
        assertTrue(
            occurrences >= 2,
            "expected the bonus-tickets conditional in both top-holder render sites; found $occurrences",
        )
    }

    @Test
    fun `player lottery template renders 'paid + bonus' breakdown when myBonusTickets is non-zero`() {
        // The "Your tickets" line shows just `myTickets` when the
        // player has no bulk-bonus tickets, and "X paid + Y bonus"
        // when they do. Pin the conditional expression so a refactor
        // can't silently lose the breakdown on initial page paint.
        assertTrue(
            lotteryHtml.contains("snapshot.weighted.myBonusTickets > 0"),
            "expected the 'Your tickets' line to branch on myBonusTickets > 0",
        )
        assertTrue(
            lotteryHtml.contains("paid +") && lotteryHtml.contains("bonus"),
            "expected the 'paid + bonus' breakdown copy in the template",
        )
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
