package toby.managers
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.jpa.service.IConfigService
import toby.menu.IMenu
import toby.menu.menus.DndMenu

internal class MenuManagerTest {

    lateinit var configService: IConfigService

    @BeforeEach
    fun setUp() {
        configService = mockk()
    }
    @AfterEach
    fun tearDown(){
        unmockkAll()
    }

    @Test
    fun testAllMenus() {
        val menuManager = MenuManager(configService)
        val availableMenus: List<Class<out IMenu>> = listOf(DndMenu::class.java)
        assertEquals(1, availableMenus.size)
        assertTrue(availableMenus.containsAll(menuManager.allMenus.map { it.javaClass }))
    }

    @Test
    fun testMenu() {
        val menuManager = MenuManager(configService)
        val menu = menuManager.getMenu("dnd")
        assertNotNull(menu)
        assertEquals("dnd", menu?.name)
    }
}
