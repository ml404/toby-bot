package configuration

import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager

@Profile("test")
@TestConfiguration
open class TestManagerConfig {

    @Bean
    open fun commandManager(): CommandManager {
        return mockk(relaxed = true)
    }

    @Bean
    open fun menuManager(): MenuManager {
        return mockk(relaxed = true)
    }

    @Bean
    open fun buttonManager(): ButtonManager {
        return mockk(relaxed = true)
    }
}