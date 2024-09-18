package configuration

import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import toby.helpers.HttpHelper

@Profile("test")
@TestConfiguration
open class TestAppConfig {

    @Bean
    open fun httpHelper(): HttpHelper {
        return mockk(relaxed = true)
    }
}