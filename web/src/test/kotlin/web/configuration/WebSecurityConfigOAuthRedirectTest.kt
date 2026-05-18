package web.configuration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The post-OAuth landing URL is configured via `defaultSuccessUrl(...)`
 * inside [WebSecurityConfig]. Before this fix the value was
 * `/intro/guilds`, which meant tapping the navbar's "Log in with Discord"
 * button from any non-protected page (homepage, leaderboard, casino
 * picker) always dumped the user on the Intro Songs guild picker.
 *
 * The contract: the default success URL is `/` (homepage). Spring's
 * saved-request behaviour still works because the second argument to
 * `defaultSuccessUrl` stays `false` — a user bounced to login from a
 * protected page still lands back on the protected page after OAuth.
 *
 * Source-text assertion (no @SpringBootTest) matches the existing
 * pattern under `web/src/test/kotlin/web/static/` (the
 * `CssRegressionTest` files) — cheap, fast, and catches the exact
 * mistake that regressed.
 */
internal class WebSecurityConfigOAuthRedirectTest {

    private val source: String by lazy {
        File("src/main/kotlin/web/configuration/WebSecurityConfig.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("WebSecurityConfig.kt not found relative to web module root")
    }

    @Test
    fun `post-OAuth default landing URL is the homepage`() {
        val expected = ".defaultSuccessUrl(\"/\", false)"
        assertTrue(
            source.replace(" ", "").contains(expected.replace(" ", "")),
            "WebSecurityConfig.kt must set $expected so users who tap Login from any non-protected page land on the homepage."
        )
    }

    @Test
    fun `post-OAuth default landing URL is no longer the intro picker`() {
        assertFalse(
            source.contains("\"/intro/guilds\""),
            "WebSecurityConfig.kt must not hard-code the intro guild picker as the default landing — that was the surprising behaviour the navbar redesign was reporting."
        )
    }

    @Test
    fun `saved-request behaviour stays enabled (alwaysUseDefaultUrl=false)`() {
        // The `false` flag preserves Spring's saved-request handling: a
        // user bounced to login from a protected page lands back there
        // after OAuth instead of the default URL. If someone flips it to
        // true the user always gets dumped on the default URL — the
        // regression this test catches.
        val flipped = ".defaultSuccessUrl(\"/\", true)"
        assertFalse(
            source.replace(" ", "").contains(flipped.replace(" ", "")),
            "WebSecurityConfig.kt must keep alwaysUseDefaultUrl=false so saved-request redirects work."
        )
    }
}
