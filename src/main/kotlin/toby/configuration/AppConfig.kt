package toby.configuration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import toby.handler.EventWaiter
import toby.helpers.HttpHelper

@Profile("prod")
@Configuration
open class AppConfig {

    @Bean
    open fun httpClient(): HttpClient {
        return HttpClient(CIO) {
        }
    }

    @Bean
    open fun httpHelper(client: HttpClient): HttpHelper {
        return HttpHelper(client)
    }

    @Bean
    open fun eventWaiter(): EventWaiter {
        return EventWaiter()
    }
}