package bot.configuration

import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

@Profile("test")
@TestConfiguration
open class TestBotConfig {

    @Bean
    open fun jda(): net.dv8tion.jda.api.JDA {
        return mockk(relaxed = true)
    }
}