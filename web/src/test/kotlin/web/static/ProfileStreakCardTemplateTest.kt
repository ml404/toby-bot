package web.static

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Pins the redesigned "Daily streak" card on the profile page.
 *
 * The card originally shipped with markup that referenced CSS classes
 * (`profile-streak`, `profile-streak-num`, `profile-streak-claim`) that
 * had **no styling defined anywhere** — so it rendered as a bare heading,
 * two raw numbers and an unstyled button. The redesign adds a flame hero,
 * a 7-day cycle tracker and a gradient claim button, all backed by real
 * CSS in `profile.css`.
 *
 * This guards three regressions:
 *
 *  1. A refactor drops the flame hero / 7-day tracker, reverting the card
 *     to the plain two-number layout.
 *  2. The `.profile-streak-num` hooks the claim JS depends on disappear,
 *     silently breaking the in-place update after claiming.
 *  3. `profile.css` loses the streak styling, leaving the markup unstyled
 *     again (the exact "very plain" state this redesign fixed).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileStreakCardTemplateTest {

    private lateinit var html: String
    private lateinit var css: String

    @BeforeAll
    fun load() {
        html = resource("templates/profile.html")
        css = resource("static/css/profile.css")
    }

    private fun resource(path: String): String =
        javaClass.classLoader.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("$path is not on the classpath")

    @Test
    fun `streak card renders a flame hero with the current streak`() {
        assertTrue(
            html.contains("class=\"profile-streak-flame\""),
            "profile.html must render a `.profile-streak-flame` hero so the daily " +
                "streak reads as a focal point, not two bare numbers.",
        )
        assertTrue(
            html.contains("profile-streak-flame-count profile-streak-num"),
            "the flame count must keep the `.profile-streak-num` hook so profile.js " +
                "can update it in place after a claim.",
        )
    }

    @Test
    fun `streak card renders the 7-day cycle tracker`() {
        assertTrue(
            html.contains("class=\"profile-streak-week\""),
            "profile.html must render the `.profile-streak-week` 7-day tracker.",
        )
        assertTrue(
            html.contains("\${#numbers.sequence(1, 7)}"),
            "the tracker must iterate a 1..7 sequence so the dots reflect the " +
                "7-day claim cycle rather than being hand-rolled markup.",
        )
    }

    @Test
    fun `claim button keeps its JS hook and gains a friendlier label`() {
        assertTrue(
            html.contains("class=\"btn profile-streak-claim\""),
            "the claim button must keep the `.profile-streak-claim` class profile.js " +
                "binds its click handler to.",
        )
        assertTrue(
            html.contains("Claim daily reward"),
            "the claim button label should read `Claim daily reward`.",
        )
    }

    @Test
    fun `streak card surfaces lifetime claims, status nudges, and the next-reward preview`() {
        assertTrue(
            html.contains("class=\"profile-streak-total\""),
            "profile.html must render the lifetime `total_claims` count " +
                "(`.profile-streak-total`) — it is persisted but was previously unused.",
        )
        assertTrue(
            html.contains("profile.streak.status == 'AT_RISK'") &&
                html.contains("profile.streak.status == 'LAPSED'"),
            "the card must branch on the derived streak status so it can nudge " +
                "an at-risk streak and flag a lapsed one, instead of a flat last-claim date.",
        )
        assertTrue(
            html.contains("profile.streak.nextRewardXp") &&
                html.contains("class=\"profile-streak-reward-pill\""),
            "the card must preview the next claim's reward (`nextRewardXp` in " +
                "`.profile-streak-reward-pill`) so the claim is motivated, not blind.",
        )
    }

    @Test
    fun `claim JS surfaces the reward the API returns and is wired to the feedback slot`() {
        val js = resource("static/js/profile.js")
        assertTrue(
            js.contains("xpGranted") && js.contains("creditsGranted") && js.contains("newBest"),
            "profile.js must read xpGranted / creditsGranted / newBest off the claim " +
                "response — they ship in the payload and were previously discarded.",
        )
        assertTrue(
            html.contains("class=\"profile-streak-claimed-reward\""),
            "profile.html must include the `.profile-streak-claimed-reward` slot the " +
                "claim handler reveals with the earned reward.",
        )
    }

    @Test
    fun `card exposes status and the milestone + reset-countdown affordances`() {
        assertTrue(
            html.contains("data-status="),
            "the card must expose `data-status` so profile.js can drive the at-risk " +
                "reset countdown without re-deriving the state.",
        )
        assertTrue(
            html.contains("class=\"profile-streak-countdown\""),
            "the AT_RISK alert must include a `.profile-streak-countdown` slot for the " +
                "live 'Resets in Xh Ym' timer.",
        )
        assertTrue(
            html.contains("class=\"muted profile-streak-milestone\"") &&
                html.contains("daysToMilestone"),
            "the card must render the `.profile-streak-milestone` line so the 7-day " +
                "tracker reads as a goal, not just decoration.",
        )
    }

    @Test
    fun `claim JS drives the countdown, milestone, and new-best celebration`() {
        val js = resource("static/js/profile.js")
        assertTrue(
            js.contains("startCountdown") && js.contains("AT_RISK"),
            "profile.js must start the reset countdown for an at-risk streak.",
        )
        assertTrue(
            js.contains("updateMilestone"),
            "profile.js must refresh the milestone line after an in-place claim.",
        )
        assertTrue(
            js.contains("celebrate") && js.contains("is-celebrating"),
            "profile.js must trigger the flame celebration when resp.newBest is set.",
        )
    }

    @Test
    fun `profile css styles the streak card so it is not plain`() {
        // The bug this redesign fixed: the markup existed but no CSS did,
        // so the card rendered unstyled. Pin that the styling is present.
        listOf(
            ".profile-streak-flame",
            ".profile-streak-week",
            ".profile-streak-day",
            ".profile-streak-claim",
            ".profile-streak-stat-chip",
            ".profile-streak-reward-pill",
            ".profile-streak-claimed-reward",
            ".profile-streak-card.is-lapsed",
            ".profile-streak-milestone",
            ".profile-streak-countdown",
            ".profile-streak-flame.is-celebrating",
        ).forEach { selector ->
            assertTrue(
                css.contains(selector),
                "profile.css must style `$selector` — without it the daily streak " +
                    "card renders plain/unstyled again.",
            )
        }
    }
}
