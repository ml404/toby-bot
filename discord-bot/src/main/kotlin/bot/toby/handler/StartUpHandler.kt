package bot.toby.handler

import common.logging.DiscordLogger
import core.managers.CommandManager
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.stereotype.Service

@Service
class StartUpHandler(
    private val commandManager: CommandManager,
    private val entryPointRegistrar: ActivityEntryPointRegistrar,
    private val logger: DiscordLogger = DiscordLogger.createLogger(StartUpHandler::class.java)
) : ListenerAdapter() {

    override fun onReady(event: ReadyEvent) {
        logger.info("${event.jda.selfUser.name} is ready")
        // The bulk overwrite REPLACES the full global command set, which
        // deletes the activity Entry Point command (JDA can't express it,
        // so it can't be in the list). Re-create it only after the
        // overwrite has landed — firing earlier would just get wiped.
        event.jda.updateCommands().addCommands(commandManager.slashCommands).queue {
            entryPointRegistrar.register(event.jda)
        }
        logger.info { "Registered ${commandManager.slashCommands.size} commands to ${event.jda.selfUser.name}" }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        logger.setGuildContext(event.guild)
        event.guild.updateCommands().queue()
        logger.info { "Reset guild commands for ${event.guild.name}" }
    }
}
