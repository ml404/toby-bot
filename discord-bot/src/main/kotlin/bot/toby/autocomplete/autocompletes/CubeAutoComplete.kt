package bot.toby.autocomplete.autocompletes

import bot.toby.command.commands.mtg.CubeCommand
import core.autocomplete.AutocompleteHandler
import database.service.user.CubeListService
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Suggests the requesting user's own saved cube names as they type the
 * `saved` option of `/cube preview` / `/cube generate`. Saved cubes are
 * keyed per Discord account, so the lookup uses the autocomplete event's
 * own user id — a user only ever sees their own cubes.
 */
@Component
class CubeAutoComplete @Autowired constructor(
    private val cubeListService: CubeListService,
) : AutocompleteHandler {

    override val name = "cube"

    override fun handle(event: CommandAutoCompleteInteractionEvent) {
        if (event.focusedOption.name != CubeCommand.OPT_SAVED) return
        val input = event.focusedOption.value
        val choices = cubeListService.listForUser(event.user.idLong)
            .asSequence()
            .map { it.name }
            .filter { it.contains(input, ignoreCase = true) }
            .take(MAX_CHOICES)
            .map { Command.Choice(it, it) }
            .toList()
        event.replyChoices(choices).queue()
    }

    companion object {
        const val MAX_CHOICES = 25
    }
}
