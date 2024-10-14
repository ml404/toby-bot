package bot.configuration

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("prod")
@Configuration
open class AppConfig {

    @Bean
    open fun httpClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
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