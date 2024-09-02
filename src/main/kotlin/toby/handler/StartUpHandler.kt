package toby.handler

import mu.KotlinLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.managers.CommandManager

@Service
@Configurable
class StartUpHandler @Autowired constructor(
    private val jda: JDA,
    private val commandManager: CommandManager
) : ListenerAdapter() {

    private val logger = KotlinLogging.logger {}

    override fun onReady(event: ReadyEvent) {
        logger.info("${event.jda.selfUser.name} is ready")
        jda.updateCommands().addCommands(commandManager.allSlashCommands).queue()
        logger.info { "Registered ${commandManager.allSlashCommands.size} commands to ${event.jda.selfUser.name}" }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        event.guild.updateCommands().queue()
        logger.info { "Reset guild commands for ${event.guild.name}" }
    }
}
