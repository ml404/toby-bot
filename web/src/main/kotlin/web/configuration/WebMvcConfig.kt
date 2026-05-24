package web.configuration

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import web.util.WebGameplayXpInterceptor

/**
 * MVC pipeline wiring. Kept separate from [WebSecurityConfig] because
 * that file's concern is the security filter chain, not the handler
 * interceptor list.
 *
 * Registers [WebGameplayXpInterceptor] against every web surface that
 * represents a user-initiated gameplay or economy action — so engaging
 * with these features through the browser awards XP the same way running
 * the equivalent Discord slash command does.
 */
@Configuration
class WebMvcConfig(
    private val webGameplayXpInterceptor: WebGameplayXpInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(webGameplayXpInterceptor)
            // Casino games (12) plus lottery — all `/casino/{guildId}/**`.
            .addPathPatterns("/casino/{guildId}/**")
            // Blackjack lives at its own root for historical reasons.
            .addPathPatterns("/blackjack/{guildId}/**")
            // Poker — multi-table cash games.
            .addPathPatterns("/poker/{guildId}/**")
            // Head-to-head matchups: duel, RPS, tic-tac-toe, connect 4.
            .addPathPatterns("/pvp/{guildId}/**")
            // Toby-coin market: buy / sell.
            .addPathPatterns("/economy/{guildId}/**")
            // Peer tipping — `/tip/{guildId}` is a single POST.
            .addPathPatterns("/tip/{guildId}")
    }
}
