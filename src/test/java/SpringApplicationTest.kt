import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import toby.Application

@SpringBootTest(classes = [Application::class])
class SpringApplicationTest {
    @Test
    fun contextLoads() {
    }
}
