package integration.web

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * End-to-end regression test for the "Log in with Discord" button
 * (`/oauth2/authorization/discord`).
 *
 * The button is a plain link; clicking it makes Spring Security's
 * OAuth2 authorization-request filter redirect to Discord with a
 * `redirect_uri` query param expanded from the `{baseUrl}` template in
 * application.properties. `{baseUrl}` is derived from the *current
 * request's* scheme/host, so behind Heroku's router it is only correct
 * once the `X-Forwarded-*` headers have been applied by the
 * ForwardedHeaderFilter.
 *
 * The Discord Activity work (#700) registered a custom ForwardedHeaderFilter
 * at an order that ran AFTER the Spring Security chain, so the headers
 * weren't applied when the redirect_uri was built — Discord then rejected
 * the redirect (internal dyno host ≠ registered URI) and the login button
 * broke. This drives the real filter stack and asserts the redirect_uri
 * reflects the forwarded host.
 *
 * Boots the same context as [PageRenderSmokeIT]; the discord client
 * registration is fully configured (provider URIs from application.properties,
 * client id/secret from application-test.properties).
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
class OAuthLoginRedirectForwardedHeaderIT {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `login button builds the redirect_uri from the X-Forwarded host`() {
        val location = mockMvc.perform(
            get("/oauth2/authorization/discord")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "www.toby-bot.co.uk")
        ).andReturn().response.getHeader("Location")
            ?: error("expected a redirect Location to Discord's authorize endpoint")

        assertTrue(
            location.startsWith("https://discord.com/api/oauth2/authorize"),
            "the login button must redirect to Discord's authorize endpoint, got: $location"
        )

        val redirectUri = UriComponentsBuilder.fromUriString(location)
            .build(true)
            .queryParams
            .getFirst("redirect_uri")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            ?: error("authorize redirect had no redirect_uri param: $location")

        assertEquals(
            "https://www.toby-bot.co.uk/login/oauth2/code/discord",
            redirectUri,
            "redirect_uri must be built from the X-Forwarded-* host so it matches Discord's registered " +
                "callback. If the ForwardedHeaderFilter runs after Spring Security it regresses to the " +
                "internal host and Discord rejects the login."
        )
    }
}
