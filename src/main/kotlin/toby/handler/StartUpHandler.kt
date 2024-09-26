package toby.handler

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.logging.DiscordLogger
import toby.managers.CommandManager

@Service
@Configurable
class StartUpHandler @Autowired constructor(
    private val jda: JDA,
    private val commandManager: CommandManager
) : ListenerAdapter() {

    lateinit var logger: DiscordLogger

    override fun onReady(event: ReadyEvent) {
        logger = DiscordLogger()
        logger.info("${event.jda.selfUser.name} is ready")
        jda.updateCommands().addCommands(commandManager.allSlashCommands).queue()
        logger.info { "Registered ${commandManager.allSlashCommands.size} commands to ${event.jda.selfUser.name}" }
        logger.info { "Commands being registered: ${commandManager.allSlashCommands.map { it?.name }}" }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        logger.setGuildAndUserContext(event.guild, null)
        event.guild.updateCommands().queue()
        logger.info { "Reset guild commands for ${event.guild.name}" }
    }
}
