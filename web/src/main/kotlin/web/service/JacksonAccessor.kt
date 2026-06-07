package web.service

import com.fasterxml.jackson.databind.JsonNode
import common.mtg.scryfall.JsonAccessor

/**
 * [JsonAccessor] over a Jackson [JsonNode], so the web's Scryfall parsing can
 * share [common.mtg.scryfall.ScryfallCardMapper] with the bot (which uses
 * Gson). Mirrors the web service's previous tolerant `path(...).asText("")`
 * extraction, so a missing/null/wrong-typed value reads as absent.
 */
class JacksonAccessor(private val node: JsonNode) : JsonAccessor {

    override fun string(key: String): String? =
        node.path(key).asText("").takeIf { it.isNotBlank() }

    override fun double(key: String, default: Double): Double =
        node.path(key).asDouble(default)

    override fun stringList(key: String): List<String> =
        node.path(key).mapNotNull { it.asText("").takeIf { s -> s.isNotBlank() } }

    override fun child(key: String): JsonAccessor? =
        node.path(key).takeIf { it.isObject }?.let(::JacksonAccessor)

    override fun children(key: String): List<JsonAccessor> =
        node.path(key).let { if (it.isArray) it.filter { e -> e.isObject }.map(::JacksonAccessor) else emptyList() }
}
