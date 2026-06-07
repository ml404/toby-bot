package bot.toby.command.commands.mtg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import common.mtg.scryfall.JsonAccessor

/**
 * [JsonAccessor] over a Gson [JsonObject], so the bot's Scryfall parsing can
 * share [common.mtg.scryfall.ScryfallCardMapper] with the web (which uses
 * Jackson). Absent / null / wrong-typed values read as absent rather than
 * throwing, matching the rest of the bot's tolerant parsing.
 */
class GsonAccessor(private val obj: JsonObject) : JsonAccessor {

    override fun string(key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    override fun double(key: String, default: Double): Double =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: default

    override fun stringList(key: String): List<String> =
        (obj.get(key) as? JsonArray)
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()

    override fun child(key: String): JsonAccessor? =
        (obj.get(key) as? JsonObject)?.let(::GsonAccessor)

    override fun children(key: String): List<JsonAccessor> =
        (obj.get(key) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::GsonAccessor) } ?: emptyList()
}
