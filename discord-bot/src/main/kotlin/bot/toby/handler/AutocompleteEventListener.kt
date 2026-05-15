package bot.toby.handler

import core.managers.AutocompleteManager
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class AutocompleteEventListener @Autowired constructor(
    private val autocompleteManager: AutocompleteManager,
) : ListenerAdapter() {

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        autocompleteManager.handle(event)
    }
}
