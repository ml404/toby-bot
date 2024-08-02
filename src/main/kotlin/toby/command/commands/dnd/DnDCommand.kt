package toby.command.commands.dnd

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
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
import toby.helpers.DnDHelper.doInitialLookup
import toby.helpers.DnDHelper.queryNonMatchRetry
import toby.helpers.DnDHelper.toEmbed
import toby.helpers.HttpHelper
import toby.jpa.dto.UserDto

class DnDCommand(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : IDnDCommand, IFetchCommand {
    private val logger = KotlinLogging.logger {}
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val typeOptionMapping = event.getOption(TYPE)
        handleWithHttpObjects(
            event,
            getName(typeOptionMapping),
            typeOptionMapping!!.asString,
            event.getOption(QUERY)!!.asString,
            HttpHelper(),
            deleteDelay
        )
    }

    @VisibleForTesting
    fun handleWithHttpObjects(
        event: SlashCommandInteractionEvent,
        typeName: String?,
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper,
        deleteDelay: Int?
    ) {
        event.deferReply().queue()
        val hook = event.hook
        logger.info("accessing DnD Api...")

        CoroutineScope(dispatcher).launch {
            val initialQuery = doInitialLookup(typeName, typeValue, query, httpHelper)
            val nonMatchQueryResult = queryNonMatchRetry(typeValue, query, httpHelper)
            if (initialQuery != null && initialQuery.isValidReturnObject()) {
                logger.info("Valid initial query found, sending embed")
                hook.sendMessageEmbeds(initialQuery.toEmbed()).queue()
            }
            if (nonMatchQueryResult != null && initialQuery == null) {
                logger.info("No initial query result, handling non-match query result")
                if (nonMatchQueryResult.count > 0) {
                    val builder = StringSelectMenu.create("dnd:$typeName").setPlaceholder("Choose an option")
                    nonMatchQueryResult.results.forEach { info: ApiInfo ->
                        builder.addOptions(
                            SelectOption.of(
                                info.index,
                                info.index
                            )
                        )
                    }
                    hook.sendMessage("Your query '$query' didn't return a value, but these close matches were found, please select one as appropriate")
                        .addActionRow(builder.build())
                        .queue()
                } else {
                    logger.info("No matches found for query: $query")
                    hook.sendMessage("Sorry, nothing was returned for $typeName '$query'")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                }
            }
        }
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
