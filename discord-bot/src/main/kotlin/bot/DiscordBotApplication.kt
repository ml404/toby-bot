package bot

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan("database", "common", "core-api", "bot", "web")
@EnableCaching
open class DiscordBotApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(DiscordBotApplication::class.java, *args)
        }
    }
}