import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import bot.toby.notify.WebPushAdapter
import common.notification.PushAdapter
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

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
@TestPropertySource(
    properties = [
        "toby.vapid.public-key=test-public-key",
        "toby.vapid.private-key=test-private-key",
        "toby.vapid.subject=mailto:ci@example.invalid",
    ]
)
class WebPushAdapterContextTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired(required = false)
    private var pushAdapter: PushAdapter? = null

    // Pre-fix this would fail at context refresh because Spring picked
    // the primary constructor and couldn't find a PushTransport bean.
    @Test
    fun `WebPushAdapter bean is registered when VAPID keys are present`() {
        assertNotNull(pushAdapter)
        assertTrue(pushAdapter is WebPushAdapter)
        assertNotNull(context.getBean(WebPushAdapter::class.java))
    }
}
