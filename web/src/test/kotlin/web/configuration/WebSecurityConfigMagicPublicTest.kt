package web.configuration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The anonymous Magic-toolkit read endpoints must stay on the permit-all
 * list in [WebSecurityConfig]; anything not listed falls through to
 * `anyRequest().authenticated()` and a browser `fetch` to it is bounced to
 * the OAuth login page (HTML), which the page's JSON handlers can't parse —
 * surfacing as a generic "Something went wrong". `/magic/api/search` was
 * added without its permit-all entry and regressed exactly that way; this
 * pins every public card endpoint so the next one can't.
 *
 * Source-text assertion (no @SpringBootTest), matching the cheap pattern in
 * [WebSecurityConfigOAuthRedirectTest].
 */
internal class WebSecurityConfigMagicPublicTest {

    private val source: String by lazy {
        File("src/main/kotlin/web/configuration/WebSecurityConfig.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("WebSecurityConfig.kt not found relative to web module root")
    }

    @Test
    fun `the anonymous magic read endpoints are all permit-all`() {
        val public = listOf(
            "/magic/api/asfan", "/magic/api/preview", "/magic/api/generate", "/magic/api/diff",
            "/magic/api/search", "/magic/api/card", "/magic/api/rulings", "/magic/api/legality",
            "/magic/api/combos", "/magic/api/set", "/magic/api/rule",
        )
        public.forEach { path ->
            assertTrue(source.contains("\"$path\"")) {
                "WebSecurityConfig.kt must permit-all $path so the anonymous Magic toolkit can call it without an OAuth redirect."
            }
        }
    }
}
