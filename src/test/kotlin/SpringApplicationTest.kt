import configuration.*
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
        TestDataSourceConfig::class,
        TestManagerConfig::class
    ]
)
@ActiveProfiles("test")
class SpringApplicationTest {
    @Test
    fun contextLoads() {
    }
}
