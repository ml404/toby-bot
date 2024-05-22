package toby.managers;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.jpa.service.IConfigService;
import toby.menu.IMenu;
import toby.menu.menus.DndMenu;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MenuManagerTest {

    @Mock
    IConfigService configService = mock(IConfigService.class);

    @Test
    void getAllMenus() {
        MenuManager menuManager = new MenuManager(configService);
        List<Class<? extends IMenu>> availableMenus = List.of(DndMenu.class);
        assertEquals(1, availableMenus.size());
        assertTrue(availableMenus.containsAll(menuManager.getAllMenus().stream().map(IMenu::getClass).toList()));
    }

    @Test
    void getMenu() {
        MenuManager menuManager = new MenuManager(configService);
        IMenu menu = menuManager.getMenu("dnd");
        assertNotNull(menu);
        assertEquals("dnd",menu.getName());
    }
}