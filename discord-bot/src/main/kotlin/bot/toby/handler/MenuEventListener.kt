package bot.toby.handler

import common.logging.DiscordLogger
import core.managers.MenuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import kotlin.coroutines.CoroutineContext

@Service
class MenuEventListener @Autowired constructor(
    private val menuManager: MenuManager,
) : ListenerAdapter(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "StringSelectInteractionEvent '${event.componentId}' received" }
        launch {
            logger.info { "Launching coroutine for '${event.componentId}'" }
            menuManager.handle(event)
        }.invokeOnCompletion {
            logger.info { "Finished coroutine for '${event.componentId}'" }
        }
    }
}
