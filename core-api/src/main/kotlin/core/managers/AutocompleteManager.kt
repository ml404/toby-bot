package core.managers

import core.autocomplete.AutocompleteHandler
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent

interface AutocompleteManager {
    val handlers: List<AutocompleteHandler>

    fun getHandler(search: String): AutocompleteHandler? = handlers.find { it.name.equals(search, true) }

    fun handle(event: CommandAutoCompleteInteractionEvent)
}
