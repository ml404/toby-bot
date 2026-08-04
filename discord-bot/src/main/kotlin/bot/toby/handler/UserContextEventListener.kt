package bot.toby.handler

import core.managers.UserContextManager
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class UserContextEventListener @Autowired constructor(
    private val userContextManager: UserContextManager,
) : ListenerAdapter() {

    override fun onUserContextInteraction(event: UserContextInteractionEvent) {
        if (event.user.isBot) return
        userContextManager.handle(event)
    }
}
