package web.configuration

import jakarta.servlet.DispatcherType
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.ForwardedHeaderFilter

/**
 * Replaces Spring Boot's auto-registered [ForwardedHeaderFilter]
 * (`server.forward-headers-strategy=framework`) with one configured for
 * relative redirects.
 *
 * Why: the default filter doesn't just apply X-Forwarded-* to the
 * request — it also wraps the response and rewrites every
 * `sendRedirect` Location into an ABSOLUTE URL on the forwarded host
 * (www.toby-bot.co.uk). Inside the Discord Activity iframe the page is
 * served via the `*.discordsays.com` proxy, so an absolute redirect to
 * the real host is a navigation out of the sandbox and Discord kills
 * the activity ("tried to open a disallowed web page"). This is also
 * why `server.tomcat.use-relative-redirects=true` alone wasn't enough —
 * Tomcat emitted a relative Location and the wrapper re-absolutised it.
 *
 * With `relativeRedirects=true` the filter leaves redirect Locations
 * exactly as the application issued them, so context-relative redirects
 * (`/login`, `/leaderboards`, …) resolve against whichever host served
 * the page: the proxy domain inside the activity, the canonical host on
 * the normal dashboard. Request-side forwarded-header processing (the
 * part OAuth2 redirect-uri building needs behind Heroku's router) is
 * unchanged.
 *
 * Boot backs off automatically: its registration is guarded by
 * `@ConditionalOnMissingFilterBean(ForwardedHeaderFilter)`. Order and
 * dispatcher types mirror Boot's own registration: `HIGHEST_PRECEDENCE`,
 * so the forwarded-header rewrite runs BEFORE the Spring Security filter
 * chain (registered at `DEFAULT_FILTER_ORDER`, i.e. -100). That ordering
 * is load-bearing: Spring Security builds the OAuth2 `redirect_uri` (and
 * saved-request URLs) from the request's scheme/host, so the
 * X-Forwarded-* headers must already be applied when the chain runs.
 * Registering this filter any later than the security chain (the earlier
 * `REQUEST_WRAPPER_FILTER_MAX_ORDER - 1` = -1 did exactly that) leaves
 * the request looking like the internal Heroku host during OAuth, so the
 * redirect_uri sent to Discord no longer matches the registered one and
 * login breaks.
 */
@Configuration
class ForwardedHeaderConfig {

    @Bean
    fun forwardedHeaderFilter(): FilterRegistrationBean<ForwardedHeaderFilter> {
        val filter = ForwardedHeaderFilter()
        filter.setRelativeRedirects(true)
        val registration = FilterRegistrationBean(filter)
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }
}
