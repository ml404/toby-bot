import bot.Application
import bot.configuration.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [
        Application::class,
        TestAppConfig::class,
        TestBotConfig::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class
    ]
)
@ActiveProfiles("test")
class SpringApplicationTest {
    @Test
    fun contextLoads() {
    }
}
