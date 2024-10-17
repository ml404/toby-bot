package bot.toby.managers

import bot.Application
import bot.configuration.*
import bot.database.service.IConfigService
import bot.toby.menu.IMenu
import bot.toby.menu.menus.EditIntroMenu
import bot.toby.menu.menus.SetIntroMenu
import bot.toby.menu.menus.dnd.DndMenu
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

    lateinit var configService: IConfigService

    @Autowired
    lateinit var menus: List<IMenu>
    private lateinit var menuManager: MenuManager


    @BeforeEach
    fun setUp() {
        configService = mockk()
        menuManager = MenuManager(configService, menus)
    }
    @AfterEach
    fun tearDown(){
        unmockkAll()
    }

    @Test
    fun testAllMenus() {
        val availableMenus: List<Class<out IMenu>> = listOf(DndMenu::class.java, SetIntroMenu::class.java, EditIntroMenu::class.java )
        assertEquals(3, menuManager.menus.size)
        assertTrue(availableMenus.containsAll(menuManager.menus.map { it.javaClass }.toList()))
    }

    @Test
    fun testMenu() {
        val menuManager = MenuManager(configService, menus)
        val menu = menuManager.getMenu("dnd")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }

    @Test
    fun testMenuWithLongerName() {
        val menuManager = MenuManager(configService, menus)
        val menu = menuManager.getMenu("dnd:spell")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }
}
