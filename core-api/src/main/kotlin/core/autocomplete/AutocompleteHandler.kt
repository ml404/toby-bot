package core.autocomplete

import core.managers.Named
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent

interface AutocompleteHandler : Named {
    override val name: String
    fun handle(event: CommandAutoCompleteInteractionEvent)
}
