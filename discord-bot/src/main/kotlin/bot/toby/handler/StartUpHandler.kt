package bot.toby.handler

import common.logging.DiscordLogger
import core.managers.CommandManager
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service

@Service
@Configurable
class StartUpHandler @Autowired constructor(
    private val jda: JDA,
    private val commandManager: CommandManager
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onReady(event: ReadyEvent) {
        logger.info("${event.jda.selfUser.name} is ready")
        jda.updateCommands().addCommands(commandManager.slashCommands).queue()
        logger.info { "Registered ${commandManager.slashCommands.size} commands to ${event.jda.selfUser.name}" }
        logger.info { "Commands being registered: ${commandManager.slashCommands.map { it?.name }}" }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        logger.setGuildContext(event.guild)
        event.guild.updateCommands().queue()
        logger.info { "Reset guild commands for ${event.guild.name}" }
    }
}
