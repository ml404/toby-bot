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
                    "/", "/terms", "/privacy", "/brother", "/config", "/music", "/user",
                    "/commands", "/commands/**", "/actuator/**",
                    "/v3/api-docs/**", "/swagger-ui/**", "/login", "/error"
                ).permitAll()
                auth.requestMatchers("/intro/**").authenticated()
                auth.anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .loginPage("/login")
                    .defaultSuccessUrl("/intro/guilds", true)
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
                csrf.ignoringRequestMatchers("/v3/api-docs/**", "/swagger-ui/**")
            }

        return http.build()
    }
}
