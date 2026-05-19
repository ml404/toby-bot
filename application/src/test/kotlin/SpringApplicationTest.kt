import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import bot.toby.notify.PushAdapter
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@ActiveProfiles("test")
class SpringApplicationTest {

    @Autowired(required = false)
    private var pushAdapter: PushAdapter? = null

    @Test
    fun contextLoads() {
    }

    @Test
    fun `WebPushAdapter is absent when no VAPID keys are configured`() {
        assertNull(pushAdapter)
    }
}
