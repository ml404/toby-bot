package toby.command.commands.dnd

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.dto.web.dnd.ApiInfo
import toby.dto.web.dnd.DnDResponse
import toby.dto.web.dnd.QueryResult
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper
import toby.helpers.MenuHelper.DND
import toby.logging.DiscordLogger

class DnDCommandQueryHandler(
    private val dispatcher: CoroutineDispatcher,
    private val httpHelper: HttpHelper,
    private val dndHelper: DnDHelper,
    private val hook: InteractionHook,
    private val deleteDelay: Int
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun processQuery(typeName: String?, typeValue: String?, query: String) {
        logger.setGuildAndUserContext(hook.interaction.guild, hook.interaction.member)
        CoroutineScope(dispatcher).launch {
            var hasReplied = false
            runCatching {
                logger.info("Accessing DnD API...")
                val initialQueryDeferred = async { dndHelper.doInitialLookup(typeName, typeValue, query, httpHelper) }
                val nonMatchQueryResultDeferred = async { dndHelper.queryNonMatchRetry(typeValue, query, httpHelper) }

                // Handle initial query result
                initialQueryDeferred.await()?.let {
                    if (it.isValidInitialQueryReturn()) {
                        hasReplied = true
                        return@launch
                    }
                }

                // Handle non-match query result
                nonMatchQueryResultDeferred.await()?.let {
                    if (it.isValidNonMatchQueryReturn(typeName, query)) {
                        hasReplied = true
                        return@launch
                    }
                }

            }.onFailure {
                logger.error { "An error occurred while handling the DnD query: $it" }
                hook.sendMessage("An error occurred while processing your request. Please try again later.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
                hasReplied = true
            }

            if (!hasReplied) {
                logger.info("No matches found for query: $query")
                hook.sendMessage("Sorry, nothing was returned for $typeName '$query'")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
    }

    private fun DnDResponse.isValidInitialQueryReturn(): Boolean {
        return if (this.isValidReturnObject()) {
            logger.info("Valid initial query found, sending embed")
            hook.sendMessageEmbeds(this.toEmbed()).queue()
            true
        } else {
            false
        }
    }

    private fun QueryResult.isValidNonMatchQueryReturn(typeName: String?, query: String): Boolean {
        logger.info("No initial query result, handling non-match query result")
        if (this.count > 0) {
            val builder = StringSelectMenu.create("$DND:$typeName").setPlaceholder("Choose an option")
            this.results.forEach { info: ApiInfo -> builder.addOptions(SelectOption.of(info.index, info.index)) }
            val stringSelectMenu = builder.build()
            hook.sendMessage("Your query '$query' didn't return a value, but these close matches were found, please select one as appropriate")
                .setActionRow(stringSelectMenu)
                .queue()
            return true
        }
        return false
    }
}
