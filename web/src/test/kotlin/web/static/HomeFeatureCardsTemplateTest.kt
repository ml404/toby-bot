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
}
