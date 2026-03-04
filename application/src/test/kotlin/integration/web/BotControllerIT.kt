package integration.web

import Application
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "spring.security.oauth2.client.registration.discord.client-id=test-client-id",
        "spring.security.oauth2.client.registration.discord.client-secret=test-client-secret"
    ]
)
class BotControllerIT {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `index endpoint redirects to login`() {
        mockMvc.perform(get("/"))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", "/login"))
    }
}
