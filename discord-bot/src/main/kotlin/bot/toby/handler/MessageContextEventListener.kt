package bot.toby.handler

import core.managers.MessageContextManager
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class MessageContextEventListener @Autowired constructor(
    private val messageContextManager: MessageContextManager,
) : ListenerAdapter() {

    override fun onMessageContextInteraction(event: MessageContextInteractionEvent) {
        if (event.user.isBot) return
        messageContextManager.handle(event)
    }
}
