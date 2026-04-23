import database.configuration.CampaignShutdownHook
import database.configuration.CampaignStartupHook
import database.configuration.FlywayGuardConfig
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Import

@SpringBootApplication(scanBasePackages = ["bot", "common", "core", "database", "web"])
@EnableCaching
@Import(FlywayGuardConfig::class, CampaignShutdownHook::class, CampaignStartupHook::class)
class Application {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(Application::class.java, *args)
        }
    }
}
