package bot.toby.autocomplete.autocompletes

import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.QUERY
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.TYPE
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.JsonParser
import core.autocomplete.AutocompleteHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class DnDAutoComplete @Autowired constructor(
    private val httpHelper: HttpHelper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutocompleteHandler {

    override val name = "dnd"

    override fun handle(event: CommandAutoCompleteInteractionEvent) {
        if (event.focusedOption.name != QUERY) return

        val typeValue = event.getOption(TYPE)?.asString
        if (typeValue.isNullOrBlank()) {
            event.replyChoices(emptyList()).queue()
            return
        }

        val input = event.focusedOption.value
        CoroutineScope(dispatcher).launch {
            val choices = fetchChoices(typeValue, input)
            event.replyChoices(choices).queue()
        }
    }

    suspend fun fetchChoices(typeValue: String, input: String): List<Command.Choice> {
        val url = if (input.isBlank()) {
            "${DnDHelper.BASE_URL}/$typeValue"
        } else {
            "${DnDHelper.BASE_URL}/$typeValue?name=${input.replace(" ", "%20")}"
        }
        val raw = runCatching { httpHelper.fetchFromGet(url) }.getOrNull()
        val queryResult = JsonParser.parseJsonToQueryResult(raw) ?: return emptyList()
        return queryResult.results
            .take(MAX_CHOICES)
            .map { Command.Choice(truncateChoice(it.name), it.index) }
    }

    private fun truncateChoice(text: String): String =
        if (text.length <= CHOICE_NAME_LIMIT) text else text.substring(0, CHOICE_NAME_LIMIT - 1) + "…"

    companion object {
        const val MAX_CHOICES = 25
        const val CHOICE_NAME_LIMIT = 100
    }
}
