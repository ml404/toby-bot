package bot.configuration

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("prod")
@Configuration
class AppConfig {

    @Bean
    fun httpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            // No global timeout (callers opt in per request, e.g. the cube
            // fetcher) — installing the plugin just enables that capability.
            install(HttpTimeout)
        }
    }

    @Bean
    fun httpHelper(client: HttpClient): HttpHelper {
        return HttpHelper(client)
    }

    @Bean
    fun eventWaiter(): EventWaiter {
        return EventWaiter()
    }
}
