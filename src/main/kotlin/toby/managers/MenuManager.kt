package toby.managers

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import toby.handler.EventWaiter
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import toby.logging.DiscordLogger
import toby.menu.IMenu
import toby.menu.MenuContext
import toby.menu.menus.EditIntroMenu
import toby.menu.menus.SetIntroMenu
import toby.menu.menus.dnd.DndMenu
import java.util.*

@Configurable
class MenuManager @Autowired constructor(private val configService: IConfigService, httpHelper: HttpHelper, introHelper: IntroHelper, userDtoHelper: UserDtoHelper, dndHelper: DnDHelper, eventWaiter: EventWaiter) {
    private val menus: MutableList<IMenu> = ArrayList()
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    init {
        addMenu(DndMenu(httpHelper = httpHelper, dnDHelper = dndHelper))
        addMenu(SetIntroMenu(introHelper, userDtoHelper))
        addMenu(EditIntroMenu(introHelper, eventWaiter))
    }

    private fun addMenu(menu: IMenu) {
        val nameFound = menus.stream().anyMatch { it: IMenu -> it.name.equals(menu.name, ignoreCase = true) }
        require(!nameFound) { "A menu with this name is already present" }
        menus.add(menu)
    }

    val allMenus: List<IMenu>
        get() = menus

    fun getMenu(search: String): IMenu? = menus.find { search.contains(it.name, ignoreCase = true) }

    fun handle(event: StringSelectInteractionEvent) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        val invoke = event.componentId.lowercase(Locale.getDefault())
        val menu = this.getMenu(invoke)

        // Build the response embed
        if (menu != null) {
            logger.info { "Handling menu: ${menu.name}" }
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

