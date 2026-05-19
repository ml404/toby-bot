package core.managers

import core.autocomplete.AutocompleteHandler
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent

interface AutocompleteManager : NamedRegistry<AutocompleteHandler> {
    val handlers: List<AutocompleteHandler>
    override val items: List<AutocompleteHandler> get() = handlers

    fun getHandler(search: String): AutocompleteHandler? = findByName(search)

    fun handle(event: CommandAutoCompleteInteractionEvent)
}
