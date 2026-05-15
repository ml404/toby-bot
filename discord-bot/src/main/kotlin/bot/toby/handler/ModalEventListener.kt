package bot.toby.handler

import core.managers.ModalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import kotlin.coroutines.CoroutineContext

@Service
class ModalEventListener @Autowired constructor(
    private val modalManager: ModalManager,
) : ListenerAdapter(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    override fun onModalInteraction(event: ModalInteractionEvent) {
        event.deferReply(true).queue()
        if (event.user.isBot) return
        launch {
            modalManager.handle(event)
        }
    }
}
