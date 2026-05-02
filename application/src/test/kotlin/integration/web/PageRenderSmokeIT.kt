package integration.web

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * End-to-end render-time smoke test for every user-facing GET page.
 *
 * Catches the broader class of regression that produced the casino
 * white-screen bug behind PR #354: Thymeleaf rendering exceptions that
 * the existing controller-level unit tests can't see (those mock the
 * service and assert on JSON, never running a template through the
 * engine). Pairs with [web.template.FragmentSignatureTest] which
 * statically validates fragment-arg counts; this one actually boots
 * Spring + Thymeleaf and asserts every page returns a non-5xx
 * response under a synthetic OAuth2 user.
 *
 * Authoritative list of routes is inline because:
 *   - reflective discovery would either miss path-template conventions
 *     or pick up internal-only endpoints, and
 *   - the explicit list doubles as documentation of what's user-facing.
 *
 * What "non-5xx" allows:
 *   - 200 OK            — page rendered
 *   - 302 redirect      — auth or guild-membership redirect (still proves
 *                         the page didn't blow up at template-render time)
 *   - 4xx               — controller-side input rejection / not-found
 *
 * What this catches: Thymeleaf parse errors, fragment-arg mismatches,
 * `${}` expression typos, missing `th:replace` targets, controller
 * exceptions that bubble through to the dispatcher.
 *
 * What this does NOT catch: visual regressions, JS errors, model-content
 * correctness — a 200 with garbage in the body still passes.
 */
@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "spring.security.oauth2.client.registration.discord.client-id=test-client-id",
        "spring.security.oauth2.client.registration.discord.client-secret=test-client-secret",
    ]
)
class PageRenderSmokeIT {

    @Autowired
    lateinit var mockMvc: MockMvc

    /** Synthetic OAuth2User with the Discord-shaped attributes our controllers read. */
    private val authUser = oauth2Login()
        .attributes { attrs ->
            attrs["id"] = "1234567890"
            attrs["username"] = "test-user"
        }

    /**
     * Every page that needs a logged-in user. We use guildId=1 and
     * tableId=1 as throwaway path values — services with mocked JDA
     * return null/empty, controllers either render a "no such guild"
     * page or redirect; either way the template runs and we'd see
     * a Thymeleaf exception before the 5xx check passes.
     */
    @ParameterizedTest(name = "GET {0} renders without server error")
    @ValueSource(
        strings = [
            // Top-level
            "/",
            "/terms",
            "/privacy",
            "/login",
            "/commands",
            "/commands/wiki",
            // Bot info pages (public)
            "/brother",
            "/config",
            "/music",
            // Casino landing + per-guild minigame pages (the cluster that 500'd in #354)
            "/casino/guilds",
            "/casino/1/slots",
            "/casino/1/dice",
            "/casino/1/coinflip",
            "/casino/1/highlow",
            "/casino/1/scratch",
            // Poker
            "/poker/guilds",
            "/poker/1",
            "/poker/1/1",
            // Blackjack
            "/blackjack/guilds",
            "/blackjack/1",
            "/blackjack/1/solo",
            "/blackjack/1/1",
            // Economy / market
            "/economy/guilds",
            "/economy/1",
            // Profile
            "/profile/guilds",
            "/profile/1",
            // Leaderboards
            "/leaderboards",
            "/leaderboard/1",
            // Duel
            "/duel/guilds",
            "/duel/1",
            // Tip
            "/tip/guilds",
            "/tip/1",
            // Titles
            "/titles/guilds",
            "/titles/1",
            // Moderation
            "/moderation/guilds",
            "/moderation/1",
            // Intros
            "/intro/guilds",
            "/intro/1",
        ]
    )
    fun pageDoesNotReturnServerError(path: String) {
        val response = mockMvc.perform(get(path).with(authUser))
            .andReturn()
            .response
        val status = response.status
        // Allow auth redirects (302/303), client errors (4xx), and successful renders.
        // Anything in the 5xx range means something blew up at render-time —
        // exactly the class of bug this test exists to catch.
        assertTrue(
            status < 500,
            "GET $path returned $status (expected non-5xx). " +
                "Likely a Thymeleaf rendering exception. Body excerpt:\n" +
                response.contentAsString.take(500)
        )
    }
}
