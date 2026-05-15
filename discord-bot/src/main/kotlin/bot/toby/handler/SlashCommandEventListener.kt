package bot.toby.handler

import common.logging.DiscordLogger
import core.managers.CommandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import kotlin.coroutines.CoroutineContext

@Service
class SlashCommandEventListener @Autowired constructor(
    private val commandManager: CommandManager,
) : ListenerAdapter(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "SlashCommandInteractionEvent '${event.name}' received" }
        if (event.user.isBot) return
        launch {
            logger.info { "Launching coroutine for '${event.name}'" }
            commandManager.handle(event)
        }.invokeOnCompletion {
            logger.info { "Finished coroutine for '${event.name}'" }
        }
    }
}
