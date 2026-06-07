package bot.toby.managers

import bot.toby.menu.DefaultMenuContext
import common.logging.DiscordLogger
import core.managers.MenuManager
import core.menu.Menu
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import java.util.*

@Configurable
class DefaultMenuManager @Autowired constructor(
    private val configService: ConfigService,
    override val menus: List<Menu>
) : MenuManager {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun getMenu(search: String): Menu? = menus.find { search.contains(it.name, ignoreCase = true) }

    override fun handle(event: StringSelectInteractionEvent) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        val invoke = event.componentId.lowercase(Locale.getDefault())
        val menu = this.getMenu(invoke)

        // Build the response embed
        if (menu != null) {
            logger.info { "Handling menu: ${menu.name}" }
            val deleteDelayConfig = configService.getConfigByName(
                ConfigDto.Configurations.DELETE_DELAY.configValue,
                event.guild!!.id)
            val ctx = DefaultMenuContext(event)
            // Ephemeral messages can't be edited via the bot webhook
            // (`event.message.edit*`) — only through the interaction hook.
            // So skip the typing indicator + disable-rows source edit for
            // ephemeral menus (e.g. the /help category drill-down); those
            // handlers re-render via deferEdit + hook.editOriginal instead.
            if (!event.message.isEphemeral) {
                event.channel.sendTyping().queue()
                val disabledActionRows = event.message.components.filterIsInstance<ActionRow>().map { it.asDisabled() }
                event.message.editMessageComponents(disabledActionRows).queue()
            }

            menu.handle(ctx, deleteDelayConfig?.value!!.toInt())
        }
    }
}

