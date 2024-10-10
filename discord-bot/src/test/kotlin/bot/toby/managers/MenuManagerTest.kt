package bot.toby.managers
import bot.toby.handler.EventWaiter
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.UserDtoHelper
import bot.toby.menu.IMenu
import bot.toby.menu.menus.EditIntroMenu
import bot.toby.menu.menus.dnd.DndMenu
import database.service.IConfigService
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class MenuManagerTest {

    lateinit var configService: IConfigService
    lateinit var httpHelper: HttpHelper
    lateinit var userDtoHelper: UserDtoHelper
    lateinit var introHelper: IntroHelper
    lateinit var dndHelper: DnDHelper
    lateinit var eventWaiter: EventWaiter

    @BeforeEach
    fun setUp() {
        configService = mockk()
        httpHelper = mockk()
        userDtoHelper = mockk()
        introHelper = mockk()
        dndHelper = mockk()
        eventWaiter = mockk()
    }
    @AfterEach
    fun tearDown(){
        unmockkAll()
    }

    @Test
    fun testAllMenus() {
        val menuManager = MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper, eventWaiter)
        val availableMenus: List<Class<out IMenu>> = listOf(DndMenu::class.java, bot.toby.menu.menus.SetIntroMenu::class.java, EditIntroMenu::class.java )
        assertEquals(3, menuManager.allMenus.size)
        assertTrue(availableMenus.containsAll(menuManager.allMenus.map { it.javaClass }.toList()))
    }

    @Test
    fun testMenu() {
        val menuManager = MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper, eventWaiter)
        val menu = menuManager.getMenu("dnd")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }

    @Test
    fun testMenuWithLongerName() {
        val menuManager =  MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper, eventWaiter)
        val menu = menuManager.getMenu("dnd:spell")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }
}
