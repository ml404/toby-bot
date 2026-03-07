package bot.toby.autocomplete.autocompletes

import core.autocomplete.AutocompleteHandler
import core.managers.CommandManager
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class HelpAutoComplete @Autowired constructor(
    private val commandManager: CommandManager
) : AutocompleteHandler {

    override val name = "help"

    override fun handle(event: CommandAutoCompleteInteractionEvent) {
        if (event.focusedOption.name == "command") {
            val input = event.focusedOption.value
            val suggestions = commandManager.commands
                .filter { it.name.contains(input, ignoreCase = true) }
                .map { it.name }
            event.replyChoices(suggestions.take(25).map { Command.Choice(it, it) }).queue()
        }
    }
}
