package web.configuration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the actuator lockdown from the security audit. Two layers, both
 * asserted here:
 *
 *  1. [WebSecurityConfig] grants anonymous access to `/actuator/health`
 *     ONLY — never the actuator wildcard matcher. With the wildcard, one
 *     stray `management.endpoints.web.exposure.include=*` (a common
 *     debugging move, settable via env var) would publish
 *     `/actuator/env` and `/actuator/heapdump` to the internet.
 *  2. `application.properties` pins the management web exposure to
 *     `health` explicitly rather than relying on Spring Boot's default.
 *
 * Source-text assertions match the existing pattern in
 * [WebSecurityConfigOAuthRedirectTest] — cheap, fast, and they catch the
 * exact one-line regressions that would reopen the hole.
 */
internal class WebSecurityConfigActuatorLockdownTest {

    private val securityConfig: String by lazy {
        File("src/main/kotlin/web/configuration/WebSecurityConfig.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("WebSecurityConfig.kt not found relative to web module root")
    }

    private val applicationProperties: String by lazy {
        File("../application/src/main/resources/application.properties")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("application.properties not found relative to web module root")
    }

    @Test
    fun `anonymous actuator access is limited to the health probe`() {
        assertTrue(
            securityConfig.contains("\"/actuator/health\""),
            "WebSecurityConfig.kt must keep /actuator/health anonymously reachable for platform health checks."
        )
    }

    @Test
    fun `the actuator wildcard is never anonymously reachable`() {
        assertFalse(
            securityConfig.contains("\"/actuator/**\""),
            "WebSecurityConfig.kt must not permitAll the /actuator/** wildcard — combined with a widened " +
                "management exposure config that would publish /actuator/env and /actuator/heapdump publicly."
        )
    }

    @Test
    fun `management web exposure is pinned to health`() {
        assertTrue(
            applicationProperties.contains("management.endpoints.web.exposure.include=health"),
            "application.properties must pin management.endpoints.web.exposure.include=health so an env var " +
                "or Boot default change can't silently widen the actuator surface."
        )
    }
}
