package configuration

import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

@Profile("test")
open class BotConfiguration {

    @Bean
    fun mockJda(): JDA {
        return mockk() // Replace with a mock JDA instance
    }
}