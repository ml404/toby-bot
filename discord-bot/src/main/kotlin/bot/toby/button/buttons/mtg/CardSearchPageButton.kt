package bot.toby.button.buttons.mtg

import bot.toby.command.commands.mtg.CardCommand
import bot.toby.command.commands.mtg.CubeEmbeds
import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Flips a `/mtgcard search` result browser to the next/previous card. Like the
 * excuse paginator, the page is stateless: the button id carries the Scryfall
 * query and the target index, so the click just re-runs the search and
 * re-renders the chosen card — no server-side session to keep alive past the
 * interaction's lifetime.
 */
@Component
class CardSearchPageButton @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Button {

    override val name: String = CardCommand.SEARCH_BUTTON
    override val description: String = "Browse the cards matching a /mtgcard search."

    // We edit the source message in place, so ack with deferEdit (no "thinking…"
    // spinner, no dangling deferred reply).
    override val defersEdit: Boolean = true

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        // Read the raw id, NOT the manager's lowercased routing copy — the
        // base64 query is case-sensitive.
        val componentId = event.componentId
        if (componentId == "${CardCommand.SEARCH_BUTTON}:noop") return // the disabled page indicator
        val parsed = CardCommand.decodeSearchButton(componentId) ?: return

        CoroutineScope(dispatcher).launch {
            runCatching {
                when (val res = fetcher.fetch(parsed.query, CardCommand.SEARCH_MAX)) {
                    is ScryfallCubeFetcher.Result.Failure ->
                        event.hook.editOriginal("🔎 Couldn't refresh that search: ${res.message}")
                            .setComponents(emptyList<MessageTopLevelComponent>()).queue({}, {})
                    is ScryfallCubeFetcher.Result.Success -> {
                        val cards = res.cards
                        if (cards.isEmpty()) {
                            event.hook.editOriginal("🔎 That search no longer matches any cards.")
                                .setComponents(emptyList<MessageTopLevelComponent>()).queue({}, {})
                            return@launch
                        }
                        val i = parsed.index.coerceIn(0, cards.lastIndex)
                        val embed = CubeEmbeds.searchResultEmbed(cards[i], i, cards.size, res.capped)
                        val content =
                            "🔎 Found **${cards.size}${if (res.capped) "+" else ""}** cards matching `${parsed.query}`."
                        val row = CardCommand.searchRow(parsed.query, i, cards.size)
                        event.hook.editOriginal(content).setEmbeds(embed)
                            .setComponents(listOfNotNull(row))
                            .queue({}, {})
                    }
                }
            }.onFailure { e ->
                logger.error("Card search pagination failed for '${parsed.query}': $e")
            }
        }
    }
}
