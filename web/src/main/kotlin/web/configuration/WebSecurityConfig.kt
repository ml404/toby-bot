package web.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class WebSecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/", "/terms", "/privacy", "/changelog",
                    "/brother", "/config", "/music", "/user",
                    "/commands", "/commands/**", "/actuator/**",
                    "/v3/api-docs/**", "/swagger-ui/**", "/login", "/error",
                    "/images/**", "/js/**", "/css/**",
                    "/dnd", "/dnd/**",
                    "/sitemap.xml", "/robots.txt"
                ).permitAll()
                auth.requestMatchers("/intro/**").authenticated()
                auth.requestMatchers("/moderation/**").authenticated()
                auth.requestMatchers("/economy/**").authenticated()
                auth.requestMatchers("/tip/**").authenticated()
                auth.requestMatchers("/duel/**").authenticated()
                auth.requestMatchers("/poker/**").authenticated()
                auth.requestMatchers("/music-player/**").authenticated()
                auth.anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .loginPage("/login")
                    .defaultSuccessUrl("/", false)
                    .failureUrl("/login?error=true")
            }
            .logout { logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
            }
            .csrf { csrf ->
                // /api/engagement/** is a JSON-only REST surface. Browser
                // clients hit it from authenticated dashboard JS that doesn't
                // round-trip the CSRF token; protection comes from the
                // OAuth2 session cookie + per-(user, guild) membership check
                // inside EngagementApiController. Mutating endpoints
                // operate only on the authenticated user's own row, so the
                // residual CSRF surface is "an attacker tricks you into
                // claiming your own daily streak" — not a meaningful risk.
                csrf.ignoringRequestMatchers(
                    "/v3/api-docs/**", "/swagger-ui/**",
                    "/api/engagement/**",
                )
            }

        return http.build()
    }
}
