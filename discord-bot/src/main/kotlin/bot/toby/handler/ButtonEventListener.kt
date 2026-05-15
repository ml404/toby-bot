package bot.toby.handler

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
) : ListenerAdapter(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.user.isBot) return
        launch {
            buttonManager.handle(event)
        }
    }
}
