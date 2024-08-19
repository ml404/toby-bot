package toby.command.commands.dnd

import kotlinx.coroutines.*
import mu.KotlinLogging
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.jetbrains.annotations.VisibleForTesting
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.fetch.IFetchCommand
import toby.dto.web.dnd.ApiInfo
import toby.dto.web.dnd.DnDResponse
import toby.dto.web.dnd.QueryResult
import toby.helpers.DnDHelper.doInitialLookup
import toby.helpers.DnDHelper.queryNonMatchRetry
import toby.helpers.DnDHelper.toEmbed
import toby.helpers.HttpHelper
import toby.jpa.dto.UserDto

class DnDCommand(private val dispatcher: CoroutineDispatcher = Dispatchers.IO, private val httpHelper: HttpHelper) :
    IDnDCommand, IFetchCommand {
    private val logger = KotlinLogging.logger {}

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val typeOptionMapping = event.getOption(TYPE)
        handleWithHttpObjects(
            event,
            getName(typeOptionMapping),
            typeOptionMapping!!.asString,
            event.getOption(QUERY)!!.asString,
            deleteDelay
        )
    }

    @VisibleForTesting
    fun handleWithHttpObjects(
        event: SlashCommandInteractionEvent,
        typeName: String?,
        typeValue: String?,
        query: String,
        deleteDelay: Int?
    ) {
        event.deferReply().queue()
        val hook = event.hook
        var messageSent = false
        logger.info("Accessing DnD API...")
        // Create a coroutine scope with a supervisor job for structured concurrency
        CoroutineScope(dispatcher).launch {
            try {
                val initialQueryDeferred = async { doInitialLookup(typeName, typeValue, query, httpHelper) }
                val nonMatchQueryResultDeferred = async { queryNonMatchRetry(typeValue, query, httpHelper) }

                // Handle the initial query result
                initialQueryDeferred.await()?.let {
                    if (it.isValidInitialQueryReturn(hook)) {
                        messageSent = true
                        return@launch
                    }
                }

                // Handle the non-match query result
                nonMatchQueryResultDeferred.await()?.let {
                    if (it.isValidNonMatchQueryReturn(typeName, hook, query)) {
                        messageSent = true
                        return@launch
                    }
                }

            } catch (e: Exception) {
                logger.error("An error occurred while handling the DnD query", e)
                hook.sendMessage("An error occurred while processing your request. Please try again later.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
                messageSent = true
            }
        }

        // Only send "No matches found" if no other message was sent
        if (!messageSent) {
            logger.info("No matches found for query: $query")
            hook.sendMessage("Sorry, nothing was returned for $typeName '$query'")
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
        }
    }


    private fun DnDResponse.isValidInitialQueryReturn(
        hook: InteractionHook
    ): Boolean {
        if (this.isValidReturnObject()) {
            logger.info("Valid initial query found, sending embed")
            hook.sendMessageEmbeds(this.toEmbed()).queue()
            return true
        }
        return false
    }

    private fun QueryResult.isValidNonMatchQueryReturn(
        typeName: String?,
        hook: InteractionHook,
        query: String
    ): Boolean {
        logger.info("No initial query result, handling non-match query result")
        if (this.count > 0) {
            val builder = StringSelectMenu.create("dnd:$typeName").setPlaceholder("Choose an option")
            this.results.forEach { info: ApiInfo -> builder.addOptions(SelectOption.of(info.index, info.index)) }
            val stringSelectMenu = builder.build()
            hook.sendMessage("Your query '$query' didn't return a value, but these close matches were found, please select one as appropriate")
                .setActionRow(stringSelectMenu)
                .queue()
            return true
        }
        return false
    }

    override val name: String
        get() = "dnd"
    override val description: String
        get() = "Use this command to do lookups on various things from DnD"
    override val optionData: List<OptionData>
        get() {
            val type = OptionData(OptionType.STRING, TYPE, "What type are you looking up", true)
            type.addChoice(SPELL_NAME, "spells")
            type.addChoice(CONDITION_NAME, "conditions")
            type.addChoice(RULE_NAME, "rule-sections")
            type.addChoice(FEATURE_NAME, "features")
            val query = OptionData(OptionType.STRING, QUERY, "What is the thing you are looking up?", true)
            return listOf(type, query)
        }

    companion object {
        const val TYPE = "type"
        const val QUERY = "query"
        const val SPELL_NAME = "spell"
        const val CONDITION_NAME = "condition"
        const val RULE_NAME = "rule"
        const val FEATURE_NAME = "feature"

        private fun getName(typeOptionMapping: OptionMapping?): String {
            return when (typeOptionMapping!!.asString) {
                "spells" -> SPELL_NAME
                "conditions" -> CONDITION_NAME
                "rule-sections" -> RULE_NAME
                "features" -> FEATURE_NAME
                else -> ""
            }
        }
    }
}
