package bot.toby.command.commands.dnd

import bot.toby.command.CommandContext
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData

class DnDCommand(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpHelper: HttpHelper,
    private val dndHelper: DnDHelper
) : IDnDCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val typeOptionMapping = event.getOption(TYPE)
        val typeName = getName(typeOptionMapping)
        val typeValue = typeOptionMapping!!.asString
        val query = event.getOption(QUERY)!!.asString
        val deleteDelay = deleteDelay ?: 0

        event.deferReply(true).queue()
        val hook = event.hook

        // Create and run coroutine scope
        DnDCommandQueryHandler(dispatcher, httpHelper, dndHelper, hook, deleteDelay).processQuery(typeName, typeValue, query)
    }

    private fun getName(typeOptionMapping: OptionMapping?): String {
        return when (typeOptionMapping!!.asString) {
            "spells" -> SPELL_NAME
            "conditions" -> CONDITION_NAME
            "rule-sections" -> RULE_NAME
            "features" -> FEATURE_NAME
            else -> ""
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
    }
}