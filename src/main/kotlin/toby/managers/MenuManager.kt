package toby.managers

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import toby.menu.IMenu
import toby.menu.MenuContext
import toby.menu.menus.DndMenu
import java.util.*

@Service
@Configurable
class MenuManager @Autowired constructor(private val configService: IConfigService) {
    private val menus: MutableList<IMenu> = ArrayList()

    init {
        addMenu(DndMenu())
    }

    private fun addMenu(menu: IMenu) {
        val nameFound = menus.stream().anyMatch { it: IMenu -> it.name.equals(menu.name, ignoreCase = true) }

        require(!nameFound) { "A menu with this name is already present" }
        menus.add(menu)
    }

    val allMenus: List<IMenu>
        get() = menus

    fun getMenu(search: String): IMenu? {
        val searchLower = search.lowercase(Locale.getDefault()).split(":".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()[0]

        for (menu in this.menus) {
            if (menu.name == searchLower) {
                return menu
            }
        }

        return null
    }

    fun handle(event: StringSelectInteractionEvent) {
        val invoke = event.componentId.lowercase(Locale.getDefault())
        val menu = this.getMenu(invoke)

        // Build the response embed
        if (menu != null) {
            val deleteDelayConfig = configService.getConfigByName(
                ConfigDto.Configurations.DELETE_DELAY.configValue, event.guild!!
                    .id
            )
            event.channel.sendTyping().queue()
            val ctx = MenuContext(event)

            menu.handle(ctx, deleteDelayConfig?.value!!.toInt())
        }
    }
}

