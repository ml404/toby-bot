package integration.web

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BotControllerIT {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `home endpoint returns 200 OK`() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
    }

    // Public routes

    @Test
    fun `terms endpoint returns 200 OK`() {
        mockMvc.perform(get("/terms"))
            .andExpect(status().isOk)
    }

    @Test
    fun `privacy endpoint returns 200 OK`() {
        mockMvc.perform(get("/privacy"))
            .andExpect(status().isOk)
    }

    @Test
    fun `login endpoint returns 200 OK`() {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk)
    }

    @Test
    fun `commands endpoint returns 200 OK`() {
        mockMvc.perform(get("/commands"))
            .andExpect(status().isOk)
    }

    @Test
    fun `commands wiki endpoint returns 200 OK`() {
        mockMvc.perform(get("/commands/wiki"))
            .andExpect(status().isOk)
    }

    // Static assets — validates the permitAll fix for /images/**, /css/**, /js/**

    @Test
    fun `preview image is publicly accessible`() {
        mockMvc.perform(get("/images/preview.svg"))
            .andExpect(status().isOk)
    }

    @Test
    fun `favicon is publicly accessible`() {
        mockMvc.perform(get("/images/favicon.svg"))
            .andExpect(status().isOk)
    }

    @Test
    fun `nav css is publicly accessible`() {
        mockMvc.perform(get("/css/nav.css"))
            .andExpect(status().isOk)
    }

    @Test
    fun `home js is publicly accessible`() {
        mockMvc.perform(get("/js/home.js"))
            .andExpect(status().isOk)
    }

    // Protected routes — unauthenticated requests should redirect to login

    @Test
    fun `intro guilds redirects unauthenticated users to login`() {
        mockMvc.perform(get("/intro/guilds"))
            .andExpect(status().is3xxRedirection())
    }

    @Test
    fun `intro page redirects unauthenticated users to login`() {
        mockMvc.perform(get("/intro/12345"))
            .andExpect(status().is3xxRedirection())
    }
}
