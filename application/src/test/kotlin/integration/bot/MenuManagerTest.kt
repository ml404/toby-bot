package integration.bot

import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import bot.toby.managers.DefaultMenuManager
import bot.toby.menu.menus.EditIntroMenu
import bot.toby.menu.menus.SetIntroMenu
import bot.toby.menu.menus.dnd.DndMenu
import common.configuration.TestCachingConfig
import core.menu.Menu
import database.configuration.TestDatabaseConfig
import database.service.ConfigService
import io.mockk.mockk
import io.mockk.unmockkAll
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
        TestAppConfig::class,
        TestBotConfig::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class
    ]
)
@ActiveProfiles("test")
internal class MenuManagerTest {

    lateinit var configService: ConfigService

    @Autowired
    lateinit var menus: List<Menu>
    private lateinit var menuManager: DefaultMenuManager


    @BeforeEach
    fun setUp() {
        configService = mockk()
        menuManager = DefaultMenuManager(configService, menus)
    }
    @AfterEach
    fun tearDown(){
        unmockkAll()
    }

    @Test
    fun testAllMenus() {
        val availableMenus: List<Class<out Menu>> =
            listOf(DndMenu::class.java, SetIntroMenu::class.java, EditIntroMenu::class.java)
        assertEquals(3, menuManager.menus.size)
        assertTrue(availableMenus.containsAll(menuManager.menus.map { it.javaClass }.toList()))
    }

    @Test
    fun testMenu() {
        val menuManager = DefaultMenuManager(configService, menus)
        val menu = menuManager.getMenu("dnd")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }

    @Test
    fun testMenuWithLongerName() {
        val menuManager = DefaultMenuManager(configService, menus)
        val menu = menuManager.getMenu("dnd:spell")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }
}
