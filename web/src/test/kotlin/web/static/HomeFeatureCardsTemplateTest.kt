package web.static

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Pins the Achievements + Notifications feature-cards on the homepage.
 *
 * Both features ship today but are otherwise undiscoverable from the
 * landing page — Achievements live behind `/profile/{guildId}` and
 * Notifications behind `/preferences/notifications`. They were added
 * to the Engagement section so newcomers see they exist before signing
 * in. This test guards against three regressions:
 *
 *  1. A future homepage rewrite quietly drops either card.
 *  2. Static copy creeps back in place of the live `${homeStats.*Count}`
 *     interpolation — which would make the homepage lie as the
 *     catalogues grow (e.g. when a new achievement or notification
 *     kind is added).
 *  3. A refactor moves the cards into the Casino or Server Management
 *     section, away from the user-progression cluster.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HomeFeatureCardsTemplateTest {

    private lateinit var html: String

    @BeforeAll
    fun loadTemplate() {
        html = javaClass.classLoader
            .getResourceAsStream("templates/home.html")
            ?.bufferedReader()
            ?.readText()
            ?: error("templates/home.html is not on the classpath")
    }

    @Test
    fun `achievements card is present on the homepage with a live count`() {
        assertTrue(
            html.contains("<h3>Achievements</h3>"),
            "home.html must include an `<h3>Achievements</h3>` feature-card heading " +
                "so the achievements system is discoverable from the landing page."
        )
        assertTrue(
            html.contains("href=\"/profile/guilds\""),
            "the achievements feature-card must link to `/profile/guilds` — the " +
                "achievements UI lives on the profile page."
        )
        assertTrue(
            html.contains("\${homeStats.achievementCount}"),
            "the achievements feature-card must interpolate " +
                "`\${homeStats.achievementCount}` so the count stays in lockstep " +
                "with AchievementCatalog as it grows. Static copy would silently " +
                "drift."
        )
    }

    @Test
    fun `notifications card is present on the homepage with a live count`() {
        assertTrue(
            html.contains("<h3>Notifications</h3>"),
            "home.html must include an `<h3>Notifications</h3>` feature-card " +
                "heading so the per-surface notification system is discoverable " +
                "from the landing page."
        )
        assertTrue(
            html.contains("href=\"/preferences/notifications\""),
            "the notifications feature-card must link to `/preferences/notifications`."
        )
        assertTrue(
            html.contains("\${homeStats.notificationKindCount}"),
            "the notifications feature-card must interpolate " +
                "`\${homeStats.notificationKindCount}` so the count stays in " +
                "lockstep with NotificationChannelKind as it grows."
        )
    }

    @Test
    fun `both new cards live in the engagement section, not casino or management`() {
        val engagementMarker = html.indexOf(">Engagement<")
        val managementMarker = html.indexOf(">Server Management<")
        val achievementsMarker = html.indexOf("<h3>Achievements</h3>")
        val notificationsMarker = html.indexOf("<h3>Notifications</h3>")

        check(engagementMarker >= 0) {
            "expected an `>Engagement<` section eyebrow on home.html"
        }
        check(managementMarker >= 0) {
            "expected a `>Server Management<` section eyebrow on home.html"
        }

        assertTrue(
            achievementsMarker in (engagementMarker + 1) until managementMarker,
            "the Achievements card must sit between the Engagement and Server " +
                "Management eyebrows so it groups with the user-progression cluster " +
                "(Profile, Levels, Leaderboards) rather than casino or moderation."
        )
        assertTrue(
            notificationsMarker in (engagementMarker + 1) until managementMarker,
            "the Notifications card must sit between the Engagement and Server " +
                "Management eyebrows."
        )
    }

    @Test
    fun `welcome and auto-role card is present in the server management section`() {
        // Pins the welcome / goodbye / auto-role feature (PR #546) on the
        // homepage. The "Server Management" section is the right home —
        // admins are the audience, and the card lives next to the
        // Moderation toolkit / Config / Polls cluster.
        assertTrue(
            html.contains("<h3>Welcome &amp; auto-role</h3>"),
            "home.html must include a `<h3>Welcome &amp; auto-role</h3>` feature-card heading " +
                "so the welcome / goodbye / auto-role feature is discoverable from the landing page."
        )
        val managementMarker = html.indexOf(">Server Management<")
        val tabletopMarker = html.indexOf(">Tabletop &amp; Tools<")
        val welcomeMarker = html.indexOf("<h3>Welcome &amp; auto-role</h3>")
        check(managementMarker >= 0) { "expected a `>Server Management<` section eyebrow on home.html" }
        check(tabletopMarker >= 0) { "expected a `>Tabletop &amp; Tools<` section eyebrow on home.html" }
        assertTrue(
            welcomeMarker in (managementMarker + 1) until tabletopMarker,
            "the Welcome & auto-role card must sit inside the Server Management section, " +
                "not engagement / casino / tabletop."
        )
    }

    @Test
    fun `magic cube card is present in the tabletop and tools section`() {
        // The MTG cube workshop (/cube) is a substantial tool — workshop,
        // the cube report, compare, card lookup — but was only reachable
        // from the navbar. Pin a discoverable feature card next to the
        // D&D / Utilities cluster it belongs with.
        assertTrue(
            html.contains("<h3>MTG cube workshop</h3>"),
            "home.html must include an `<h3>MTG cube workshop</h3>` feature-card heading " +
                "so the Magic cube tooling is discoverable from the landing page."
        )
        assertTrue(
            html.contains("href=\"/cube\""),
            "the MTG cube feature-card must link to `/cube` — the cube workshop page."
        )
        val tabletopMarker = html.indexOf(">Tabletop &amp; Tools<")
        val ctaMarker = html.indexOf("Ready to add")
        val cubeMarker = html.indexOf("<h3>MTG cube workshop</h3>")
        check(tabletopMarker >= 0) { "expected a `>Tabletop &amp; Tools<` section eyebrow on home.html" }
        check(ctaMarker >= 0) { "expected the final CTA on home.html" }
        assertTrue(
            cubeMarker in (tabletopMarker + 1) until ctaMarker,
            "the MTG cube card must sit inside the Tabletop & Tools section, " +
                "grouped with D&D lookups and Utilities."
        )
    }

    @Test
    fun `how-it-works step 1 mentions the in-Discord install wizard`() {
        // PR #534 shipped the /install wizard. The "How it works"
        // first step used to imply admins set everything via slash
        // commands; pin that the install wizard is now called out so
        // owners know there's a guided onboarding path.
        val howtoMarker = html.indexOf(">How it works<")
        val casinoMarker = html.indexOf(">Casino &amp; Coin<")
        check(howtoMarker >= 0) { "expected a `>How it works<` section eyebrow on home.html" }
        check(casinoMarker >= 0) { "expected a `>Casino &amp; Coin<` section eyebrow on home.html" }
        val howtoSection = html.substring(howtoMarker, casinoMarker)
        assertTrue(
            howtoSection.contains("<code>/install</code>"),
            "the `How it works` step 1 must reference the `/install` wizard so owners " +
                "discover the guided setup path, not just the slash-command-only setup."
        )
    }

    @Test
    fun `profile feature card mentions the slash-command PNG render`() {
        // PR #548 shipped /profile + a PNG card. Pin that the homepage
        // surfaces the slash-command path, not just the HTML profile
        // page — otherwise members never discover the shareable PNG.
        val profileMarker = html.indexOf("<h3>Profile</h3>")
        check(profileMarker >= 0) { "expected a `<h3>Profile</h3>` feature card on home.html" }
        // The card's `<p>` body sits immediately after the heading; walk
        // to the next `</a>` (the card root) to bound the search.
        val cardEnd = html.indexOf("</a>", profileMarker)
        check(cardEnd > profileMarker) { "Profile card not terminated" }
        val card = html.substring(profileMarker, cardEnd)
        assertTrue(
            card.contains("<code>/profile</code>"),
            "the Profile feature card must reference `/profile` so members discover " +
                "the slash-command PNG render, not just the HTML profile page:\n$card"
        )
    }
}
