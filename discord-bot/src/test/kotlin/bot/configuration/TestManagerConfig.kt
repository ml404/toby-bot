package bot.configuration

import bot.toby.managers.DefaultButtonManager
import bot.toby.managers.DefaultCommandManager
import bot.toby.managers.DefaultMenuManager
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

@Profile("test")
@TestConfiguration
open class TestManagerConfig {

    @Bean
    open fun commandManager(): DefaultCommandManager {
        return mockk(relaxed = true)
    }

    @Bean
    open fun menuManager(): DefaultMenuManager {
        return mockk(relaxed = true)
    }

    @Bean
    open fun buttonManager(): DefaultButtonManager {
        return mockk(relaxed = true)
    }
}