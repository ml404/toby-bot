import configuration.TestAppConfig
import configuration.TestBotConfig
import configuration.TestCachingConfig
import configuration.TestManagerConfig
import database.configuration.TestDatabaseConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import toby.Application

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
