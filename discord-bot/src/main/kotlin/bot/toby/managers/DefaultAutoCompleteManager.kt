package bot.toby.managers

import core.autocomplete.AutocompleteHandler
import core.managers.AutocompleteManager
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable

@Configurable
class DefaultAutoCompleteManager @Autowired constructor(
    override val handlers: List<AutocompleteHandler>
) : AutocompleteManager {

    override fun handle(event: CommandAutoCompleteInteractionEvent) {
        getHandler(event.name)?.handle(event)
    }
}
