package toby.managers
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.jpa.service.IConfigService
import toby.menu.IMenu
import toby.menu.IntroMenu
import toby.menu.menus.dnd.DndMenu

internal class MenuManagerTest {

    lateinit var configService: IConfigService
    lateinit var httpHelper: HttpHelper
    lateinit var userDtoHelper: UserDtoHelper
    lateinit var introHelper: IntroHelper
    lateinit var dndHelper: DnDHelper

    @BeforeEach
    fun setUp() {
        configService = mockk()
        httpHelper = mockk()
        userDtoHelper = mockk()
        introHelper = mockk()
        dndHelper = mockk()
    }
    @AfterEach
    fun tearDown(){
        unmockkAll()
    }

    @Test
    fun testAllMenus() {
        val menuManager = MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper)
        val availableMenus: List<Class<out IMenu>> = listOf(DndMenu::class.java, IntroMenu::class.java )
        assertEquals(2, menuManager.allMenus.size)
        assertTrue(availableMenus.containsAll(menuManager.allMenus.map { it.javaClass }.toList()))
    }

    @Test
    fun testMenu() {
        val menuManager = MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper)
        val menu = menuManager.getMenu("dnd")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }

    @Test
    fun testMenuWithLongerName() {
        val menuManager =  MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper)
        val menu = menuManager.getMenu("dnd:spell")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }
}
