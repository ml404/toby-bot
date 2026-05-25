package bot.toby.handler

import common.logging.DiscordLogger
import core.managers.ButtonManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import kotlin.coroutines.CoroutineContext

@Service
class ButtonEventListener @Autowired constructor(
    private val buttonManager: ButtonManager,
    // Tests can pass Dispatchers.Unconfined so launch{} resolves synchronously.
    dispatcher: CoroutineContext = SupervisorJob() + Dispatchers.Default,
) : ListenerAdapter(), CoroutineScope {

    override val coroutineContext: CoroutineContext = dispatcher
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.user.isBot) return
        launch {
            try {
                buttonManager.handle(event)
            } catch (t: Throwable) {
                logger.error("Button '${event.componentId}' threw — resolving the deferred reply", t)
                resolveFailedInteraction(event)
            }
        }
    }

    // If a button handler throws after the manager's ephemeral defer,
    // Discord keeps showing "Bot is thinking…" forever. Resolve the
    // spinner with a user-facing error so the interaction closes.
    private fun resolveFailedInteraction(event: ButtonInteractionEvent) {
        val message = "Something went wrong handling that button. Try again in a moment."
        runCatching {
            if (event.isAcknowledged) {
                event.hook.editOriginal(message).queue({}, {})
            } else {
                event.reply(message).setEphemeral(true).queue({}, {})
            }
        }
    }
}
