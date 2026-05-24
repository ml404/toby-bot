package bot.toby.command.commands.dnd

import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import core.command.CommandContext
import database.dto.user.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class DnDSearchCommand @Autowired constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpHelper: HttpHelper,
    private val dndHelper: DnDHelper
) : DnDCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val typeOptionMapping = event.getOption(TYPE)
        val typeName = getName(typeOptionMapping)
        val typeValue = typeOptionMapping!!.asString
        val query = event.getOption(QUERY)!!.asString
        val deleteDelay = deleteDelay

        event.deferReply(true).queue()
        val hook = event.hook

        // Create and run coroutine scope
        DnDCommandQueryHandler(dispatcher, httpHelper, dndHelper, hook, deleteDelay).processQuery(
            typeName,
            typeValue,
            query
        )
    }

    private fun getName(typeOptionMapping: OptionMapping?): String {
        return when (typeOptionMapping!!.asString) {
            "spells" -> SPELL_NAME
            "conditions" -> CONDITION_NAME
            "rule-sections" -> RULE_NAME
            "features" -> FEATURE_NAME
            "ability-scores" -> ABILITY_SCORE_NAME
            "classes" -> CLASS_NAME
            "damage-types" -> DAMAGE_TYPE_NAME
            "equipment-categories" -> EQUIPMENT_CATEGORY_NAME
            "equipment" -> EQUIPMENT_NAME
            "languages" -> LANGUAGE_NAME
            "magic-schools" -> MAGIC_SCHOOL_NAME
            "monsters" -> MONSTER_NAME
            "proficiencies" -> PROFICIENCY_NAME
            "races" -> RACE_NAME
            "skills" -> SKILL_NAME
            "subclasses" -> SUBCLASS_NAME
            "subraces" -> SUBRACE_NAME
            "traits" -> TRAIT_NAME
            "weapon-properties" -> WEAPON_PROPERTY_NAME
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
            type.addChoice(ABILITY_SCORE_NAME, "ability-scores")
            type.addChoice(CLASS_NAME, "classes")
            type.addChoice(DAMAGE_TYPE_NAME, "damage-types")
            type.addChoice(EQUIPMENT_CATEGORY_NAME, "equipment-categories")
            type.addChoice(EQUIPMENT_NAME, "equipment")
            type.addChoice(LANGUAGE_NAME, "languages")
            type.addChoice(MAGIC_SCHOOL_NAME, "magic-schools")
            type.addChoice(MONSTER_NAME, "monsters")
            type.addChoice(PROFICIENCY_NAME, "proficiencies")
            type.addChoice(RACE_NAME, "races")
            type.addChoice(SKILL_NAME, "skills")
            type.addChoice(SUBCLASS_NAME, "subclasses")
            type.addChoice(SUBRACE_NAME, "subraces")
            type.addChoice(TRAIT_NAME, "traits")
            type.addChoice(WEAPON_PROPERTY_NAME, "weapon-properties")
            val query = OptionData(OptionType.STRING, QUERY, "What is the thing you are looking up?", true)
                .setAutoComplete(true)
            return listOf(type, query)
        }

    companion object {
        const val TYPE = "type"
        const val QUERY = "query"
        const val SPELL_NAME = "spell"
        const val CONDITION_NAME = "condition"
        const val RULE_NAME = "rule"
        const val FEATURE_NAME = "feature"
        const val ABILITY_SCORE_NAME = "ability-score"
        const val CLASS_NAME = "class"
        const val DAMAGE_TYPE_NAME = "damage-type"
        const val EQUIPMENT_CATEGORY_NAME = "equipment-category"
        const val EQUIPMENT_NAME = "equipment"
        const val LANGUAGE_NAME = "language"
        const val MAGIC_SCHOOL_NAME = "magic-school"
        const val MONSTER_NAME = "monster"
        const val PROFICIENCY_NAME = "proficiency"
        const val RACE_NAME = "race"
        const val SKILL_NAME = "skill"
        const val SUBCLASS_NAME = "subclass"
        const val SUBRACE_NAME = "subrace"
        const val TRAIT_NAME = "trait"
        const val WEAPON_PROPERTY_NAME = "weapon-property"
    }
}
