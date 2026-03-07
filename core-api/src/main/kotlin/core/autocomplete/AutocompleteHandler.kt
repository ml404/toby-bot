package core.autocomplete

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent

interface AutocompleteHandler {
    val name: String
    fun handle(event: CommandAutoCompleteInteractionEvent)
}
