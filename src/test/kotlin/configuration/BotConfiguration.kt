package configuration

import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import toby.BotMain

@TestConfiguration
open class BotConfiguration {

    @Bean
    fun mockJda(): JDA {
        return mockk()
    }

    @Bean
    fun mockBotMain(): BotMain {
        return mockk()
    }
}