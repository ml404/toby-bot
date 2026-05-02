package integration.bot

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import bot.toby.autocomplete.autocompletes.HelpAutoComplete
import bot.toby.managers.DefaultAutoCompleteManager
import common.configuration.TestCachingConfig
import core.autocomplete.AutocompleteHandler
import database.configuration.TestDatabaseConfig
import io.mockk.*
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
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

    @Test
    fun testHandle() {
        val event = mockk<CommandAutoCompleteInteractionEvent> {
            every { name } returns "help"
        }
        val handler = mockk<AutocompleteHandler> {
            every { name } returns "help"
            every { handle(event) } just Runs
        }
        val manager = DefaultAutoCompleteManager(listOf(handler))
        manager.handle(event)
        verify(exactly = 1) { handler.handle(event) }
    }
}
