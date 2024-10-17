package bot.toby.managers

import bot.toby.menu.IMenu
import bot.toby.menu.MenuContext
import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.service.IConfigService
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import java.util.*

@Configurable
class MenuManager @Autowired constructor(private val configService: IConfigService, val menus: List<IMenu>) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

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

