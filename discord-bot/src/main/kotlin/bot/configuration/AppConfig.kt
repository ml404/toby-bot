package bot.configuration

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

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