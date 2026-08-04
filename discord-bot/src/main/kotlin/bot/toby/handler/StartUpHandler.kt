package bot.toby.handler

import common.logging.DiscordLogger
import core.managers.CommandManager
import core.managers.MessageContextManager
import core.managers.UserContextManager
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.stereotype.Service

@Service
class StartUpHandler(
    private val commandManager: CommandManager,
    private val messageContextManager: MessageContextManager,
    private val userContextManager: UserContextManager,
    private val globalCommandRegistrar: GlobalCommandRegistrar,
    private val logger: DiscordLogger = DiscordLogger.createLogger(StartUpHandler::class.java)
) : ListenerAdapter() {

    override fun onReady(event: ReadyEvent) {
        logger.info("${event.jda.selfUser.name} is ready")
        // Slash commands and right-click Apps entries share one global command
        // set, so they go up in a single overwrite. That overwrite runs through
        // GlobalCommandRegistrar rather than jda.updateCommands() because
        // Discord rejects any bulk update that would drop the Activity Entry
        // Point command, which JDA can't express — see that class for detail.
        val slashCommands = commandManager.slashCommands.filterNotNull()
        val contextCommands = messageContextManager.contextCommandData + userContextManager.contextCommandData
        globalCommandRegistrar.register(event.jda, slashCommands + contextCommands)

        logger.info {
            "Submitted ${slashCommands.size} commands and ${contextCommands.size} context menu entries " +
                "for ${event.jda.selfUser.name}"
        }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        logger.setGuildContext(event.guild)
        event.guild.updateCommands().queue()
        logger.info { "Reset guild commands for ${event.guild.name}" }
    }
}
