package bot.toby.managers

import bot.database.dto.ConfigDto
import bot.database.service.IConfigService
import bot.logging.DiscordLogger
import bot.toby.handler.EventWaiter
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.UserDtoHelper
import bot.toby.menu.IMenu
import bot.toby.menu.MenuContext
import bot.toby.menu.menus.EditIntroMenu
import bot.toby.menu.menus.SetIntroMenu
import bot.toby.menu.menus.dnd.DndMenu
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
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

