package toby.managers;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.stereotype.Service;
import toby.jpa.dto.ConfigDto;
import toby.jpa.service.IConfigService;
import toby.menu.IMenu;
import toby.menu.MenuContext;
import toby.menu.menus.DndMenu;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

@Service
@Configurable
public class MenuManager {
    private final List<IMenu> menus = new ArrayList<>();
    private final IConfigService configService;

    @Autowired
    public MenuManager(IConfigService configService) {
        this.configService = configService;
        addMenu(new DndMenu());
    }

    private void addMenu(IMenu menu) {
        boolean nameFound = this.menus.stream().anyMatch((it) -> it.getName().equalsIgnoreCase(menu.getName()));

        if (nameFound) {
            throw new IllegalArgumentException("A menu with this name is already present");
        }
        menus.add(menu);

    }

    public List<IMenu> getAllMenus() {
        return menus;
    }

    @Nullable
    public IMenu getMenu(String search) {
        String searchLower = search.toLowerCase().split(":")[0];

        for (IMenu menu : this.menus) {
            if (menu.getName().equals(searchLower)) {
                return menu;
            }
        }

        return null;
    }

    public void handle(StringSelectInteractionEvent event) {
        String invoke = event.getComponentId().toLowerCase();
        IMenu menu = this.getMenu(invoke);

        // Build the response embed
        if (menu != null) {
            ConfigDto deleteDelayConfig = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.getConfigValue(), event.getGuild().getId());
            event.getChannel().sendTyping().queue();
            MenuContext ctx = new MenuContext(event);

            menu.handle(ctx, Integer.valueOf(deleteDelayConfig.getValue()));
        }
    }

}

