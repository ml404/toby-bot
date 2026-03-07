package integration.bot

import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import bot.toby.autocomplete.autocompletes.HelpAutoComplete
import bot.toby.managers.DefaultAutoCompleteManager
import common.configuration.TestCachingConfig
import core.autocomplete.AutocompleteHandler
import database.configuration.TestDatabaseConfig
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
internal class AutocompleteManagerTest {

    @Autowired
    lateinit var handlers: List<AutocompleteHandler>

    private lateinit var autocompleteManager: DefaultAutoCompleteManager

    @BeforeEach
    fun setUp() {
        autocompleteManager = DefaultAutoCompleteManager(handlers)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testAllHandlers() {
        val availableHandlers: List<Class<out AutocompleteHandler>> = listOf(HelpAutoComplete::class.java)
        assertEquals(1, autocompleteManager.handlers.size)
        assertTrue(availableHandlers.containsAll(autocompleteManager.handlers.map { it.javaClass }.toList()))
    }

    @Test
    fun testGetHandler() {
        val handler = autocompleteManager.getHandler("help")
        assertNotNull(handler)
        assertEquals("help", handler?.name)
    }
}
