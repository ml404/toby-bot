package bot.configuration

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import io.ktor.client.HttpClient
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

@Profile("test")
@TestConfiguration
open class TestAppConfig {

    // The cube fetcher injects the shared ktor client; the integration
    // context never makes real Scryfall calls, so a relaxed mock suffices.
    @Bean
    open fun httpClient(): HttpClient {
        return mockk(relaxed = true)
    }

    @Bean
    open fun httpHelper(): HttpHelper {
        return mockk(relaxed = true)
    }

    @Bean
    open fun eventWaiter(): EventWaiter {
        return mockk(relaxed = true)
    }
}