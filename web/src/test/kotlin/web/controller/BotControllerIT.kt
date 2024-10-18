package web.controller

import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import web.WebApplication

@SpringBootTest(
    classes = [
        WebApplication::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BotControllerIT {

    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        // Setup code if needed
    }

    @Test
    fun `index endpoint returns welcome message`() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Welcome to TobyBot \nTo find out more, please visit https://github.com/ml404/toby-bot#readme")))
    }
}
