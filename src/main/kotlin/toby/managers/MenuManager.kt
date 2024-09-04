package toby.managers

import mu.KotlinLogging
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import toby.helpers.HttpHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import toby.menu.IMenu
import toby.menu.MenuContext
import toby.menu.menus.dnd.DndMenu
import java.util.*

@Configurable
class MenuManager @Autowired constructor(private val configService: IConfigService, httpHelper: HttpHelper) {
    private val menus: MutableList<IMenu> = ArrayList()
    private val logger = KotlinLogging.logger {}

    init {
        addMenu(DndMenu(httpHelper = httpHelper))
    }

    private fun addMenu(menu: IMenu) {
        val nameFound = menus.stream().anyMatch { it: IMenu -> it.name.equals(menu.name, ignoreCase = true) }
        require(!nameFound) { "A menu with this name is already present" }
        menus.add(menu)
        logger.info { "Added menu ${menu.name}" }
    }

    val allMenus: List<IMenu>
        get() = menus

    fun getMenu(search: String): IMenu? = menus.find { search.contains(it.name, ignoreCase = true) }

    fun handle(event: StringSelectInteractionEvent) {
        val invoke = event.componentId.lowercase(Locale.getDefault())
        val menu = this.getMenu(invoke)

        // Build the response embed
        if (menu != null) {
            logger.info { "Handling menu: ${menu.name} on guild: ${event.guild?.idLong}" }
            val deleteDelayConfig = configService.getConfigByName(
                ConfigDto.Configurations.DELETE_DELAY.configValue,
                event.guild!!.id)
            event.channel.sendTyping().queue()
            val ctx = MenuContext(event)
            val disabledActionRows = event.message.actionRows.map { it.asDisabled() }
            event.message.editMessageComponents(disabledActionRows).queue()

            menu.handle(ctx, deleteDelayConfig?.value!!.toInt())
        }
    }
}

