package bot.toby.helpers

import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.ABILITY_SCORE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.CLASS_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.CONDITION_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.DAMAGE_TYPE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.EQUIPMENT_CATEGORY_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.EQUIPMENT_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.FEATURE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.LANGUAGE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.MAGIC_SCHOOL_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.MONSTER_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.PROFICIENCY_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.RACE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.RULE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.SKILL_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.SPELL_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.SUBCLASS_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.SUBRACE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.TRAIT_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.WEAPON_PROPERTY_NAME
import bot.toby.dto.web.dnd.DnDResponse
import bot.toby.dto.web.dnd.QueryResult
import common.logging.DiscordLogger
import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class DnDHelper {

    private val logger = DiscordLogger(this::class.java)

    fun rollDice(diceValue: Int, diceToRoll: Int): Int =
        rollDiceList(diceValue, diceToRoll).sum()

    /** Same RNG path as [rollDice] but keeps the individual values so
     *  callers can show a per-die breakdown. */
    fun rollDiceList(diceValue: Int, diceToRoll: Int): List<Int> =
        (0 until diceToRoll).map { Random.nextInt(1, diceValue + 1) }

    suspend fun doInitialLookup(
        typeName: String?,
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper
    ): DnDResponse? {
        val url = "$BASE_URL/$typeValue/${query.replaceSpaceWithDash()}"
        logger.info("Fetching data from '$url'")
        val responseData = httpHelper.fetchFromGet(url)
        return when (typeName) {
            SPELL_NAME -> JsonParser.parseJSONToSpell(responseData)
            CONDITION_NAME -> JsonParser.parseJsonToCondition(responseData)
            RULE_NAME -> JsonParser.parseJsonToRule(responseData)
            FEATURE_NAME -> JsonParser.parseJsonToFeature(responseData)
            ABILITY_SCORE_NAME -> JsonParser.parseJsonToAbilityScore(responseData)
            CLASS_NAME -> JsonParser.parseJsonToDnDClass(responseData)
            DAMAGE_TYPE_NAME -> JsonParser.parseJsonToDamageTypeInfo(responseData)
            EQUIPMENT_CATEGORY_NAME -> JsonParser.parseJsonToEquipmentCategory(responseData)
            EQUIPMENT_NAME -> JsonParser.parseJsonToEquipment(responseData)
            LANGUAGE_NAME -> JsonParser.parseJsonToLanguage(responseData)
            MAGIC_SCHOOL_NAME -> JsonParser.parseJsonToMagicSchool(responseData)
            MONSTER_NAME -> JsonParser.parseJsonToMonster(responseData)
            PROFICIENCY_NAME -> JsonParser.parseJsonToProficiency(responseData)
            RACE_NAME -> JsonParser.parseJsonToRace(responseData)
            SKILL_NAME -> JsonParser.parseJsonToSkill(responseData)
            SUBCLASS_NAME -> JsonParser.parseJsonToSubclass(responseData)
            SUBRACE_NAME -> JsonParser.parseJsonToSubrace(responseData)
            TRAIT_NAME -> JsonParser.parseJsonToTrait(responseData)
            WEAPON_PROPERTY_NAME -> JsonParser.parseJsonToWeaponProperty(responseData)
            else -> null
        }
    }

    suspend fun queryNonMatchRetry(
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper
    ): QueryResult? {
        val queryUrl = "$BASE_URL/$typeValue?name=${query.replaceSpaceWithUrlEncode()}"
        logger.info("Fetching data from '$queryUrl'")
        val queryResponseData = httpHelper.fetchFromGet(queryUrl)
        return JsonParser.parseJsonToQueryResult(queryResponseData)
    }

    private fun String.replaceSpaceWithDash(): String = this.replace(" ", "-")
    private fun String.replaceSpaceWithUrlEncode(): String = this.replace(" ", "%20")

    companion object {
        const val BASE_URL = "https://www.dnd5eapi.co/api"
    }
}
