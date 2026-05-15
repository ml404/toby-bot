package bot.toby.helpers

import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.CONDITION_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.FEATURE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.RULE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.SPELL_NAME
import bot.toby.dto.web.dnd.DnDResponse
import bot.toby.dto.web.dnd.QueryResult
import common.logging.DiscordLogger
import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class DnDHelper {

    private val logger = DiscordLogger(this::class.java)

    fun rollDice(diceValue: Int, diceToRoll: Int): Int =
        (0 until diceToRoll).sumOf { Random.nextInt(1, diceValue + 1) }

    suspend fun doInitialLookup(
        typeName: String?,
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper
    ): DnDResponse? {
        val url = "https://www.dnd5eapi.co/api/$typeValue/${query.replaceSpaceWithDash()}"
        logger.info("Fetching data from '$url'")
        val responseData = httpHelper.fetchFromGet(url)
        return when (typeName) {
            SPELL_NAME -> JsonParser.parseJSONToSpell(responseData)
            CONDITION_NAME -> JsonParser.parseJsonToCondition(responseData)
            RULE_NAME -> JsonParser.parseJsonToRule(responseData)
            FEATURE_NAME -> JsonParser.parseJsonToFeature(responseData)
            else -> null
        }
    }

    suspend fun queryNonMatchRetry(
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper
    ): QueryResult? {
        val queryUrl = "https://www.dnd5eapi.co/api/$typeValue?name=${query.replaceSpaceWithUrlEncode()}"
        logger.info("Fetching data from '$queryUrl'")
        val queryResponseData = httpHelper.fetchFromGet(queryUrl)
        return JsonParser.parseJsonToQueryResult(queryResponseData)
    }

    private fun String.replaceSpaceWithDash(): String = this.replace(" ", "-")
    private fun String.replaceSpaceWithUrlEncode(): String = this.replace(" ", "%20")
}
