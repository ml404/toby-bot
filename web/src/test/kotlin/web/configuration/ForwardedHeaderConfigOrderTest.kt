package web.configuration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.core.Ordered

/**
 * Regression guard for OAuth login behind Heroku's router.
 *
 * [ForwardedHeaderConfig] replaces Boot's auto-registered
 * [org.springframework.web.filter.ForwardedHeaderFilter] so it can keep
 * redirect Locations relative (for the Discord Activity sandbox). The
 * registration MUST run before the Spring Security filter chain, because
 * Spring Security builds the OAuth2 `redirect_uri` (and saved-request
 * URLs) from the request's scheme/host — the X-Forwarded-* rewrite has to
 * land first or the redirect_uri reflects the internal dyno host and
 * Discord rejects it.
 *
 * The original commit registered the filter at
 * `REQUEST_WRAPPER_FILTER_MAX_ORDER - 1` (= -1), which is AFTER the
 * security chain ([SecurityProperties.DEFAULT_FILTER_ORDER] = -100), and
 * that broke login. This pins the order to Boot's own value
 * (`HIGHEST_PRECEDENCE`) and asserts it stays ahead of the security chain.
 */
internal class ForwardedHeaderConfigOrderTest {

    private val registration = ForwardedHeaderConfig().forwardedHeaderFilter()

    @Test
    fun `forwarded-header filter is registered at HIGHEST_PRECEDENCE like Boot`() {
        assertEquals(
            Ordered.HIGHEST_PRECEDENCE,
            registration.order,
            "ForwardedHeaderFilter must mirror Boot's HIGHEST_PRECEDENCE registration."
        )
    }

    @Test
    fun `forwarded-header filter runs before the Spring Security filter chain`() {
        assertTrue(
            registration.order < SecurityProperties.DEFAULT_FILTER_ORDER,
            "ForwardedHeaderFilter (order ${registration.order}) must run before the Spring Security " +
                "chain (order ${SecurityProperties.DEFAULT_FILTER_ORDER}); otherwise the X-Forwarded-* " +
                "headers aren't applied when Spring Security builds the OAuth2 redirect_uri and login breaks."
        )
    }
}
