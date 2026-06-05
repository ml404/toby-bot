package bot.toby.command.commands.mtg

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import common.logging.DiscordLogger
import common.mtg.CubeCard
import common.mtg.MtgColor
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.springframework.stereotype.Component
import java.io.InputStreamReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pulls a cube's card pool from the public Scryfall search API
 * (`/cards/search`). A "cube" here is just whatever a Scryfall query
 * matches — e.g. `set:vow`, `is:commander`, `t:dragon`, or a curated
 * `cube:<name>` — so a user defines their pool with the same search
 * syntax they'd use on scryfall.com.
 *
 * Mirrors the HTTP/JSON approach of [bot.toby.command.commands.fetch.MemeCommand]:
 * an injectable Apache [HttpClient] (so tests stub the transport) and
 * Gson tree parsing. The card → [CubeCard] mapping is split into
 * [parseCard] so it can be unit-tested without any network.
 */
@Component
class ScryfallCubeFetcher {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val gson: Gson = Gson()

    sealed interface Result {
        data class Success(val cards: List<CubeCard>) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Fetches up to [maxCards] cards matching [query], following Scryfall's
     * pagination. Creates a default HTTP client; the overload taking a
     * client is the test seam.
     */
    fun fetch(query: String, maxCards: Int = DEFAULT_MAX_CARDS): Result =
        fetch(query, maxCards, HttpClients.createDefault())

    fun fetch(query: String, maxCards: Int, httpClient: HttpClient): Result {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result.Failure("Give me a Scryfall search query (e.g. `set:vow`).")

        val cards = mutableListOf<CubeCard>()
        var url: String? = searchUrl(trimmed)
        var page = 0

        try {
            while (url != null && cards.size < maxCards && page < MAX_PAGES) {
                if (page > 0) Thread.sleep(SCRYFALL_DELAY_MS) // be polite to the API
                val response = httpClient.execute(HttpGet(url))
                val status = response.statusLine.statusCode
                if (status == 404) {
                    EntityUtils.consume(response.entity)
                    return Result.Failure("No cards matched `$trimmed`.")
                }
                if (status != 200) {
                    EntityUtils.consume(response.entity)
                    return Result.Failure("Scryfall returned HTTP $status. Try again later.")
                }
                val root = JsonParser.parseReader(InputStreamReader(response.entity.content)).asJsonObject
                EntityUtils.consume(response.entity)

                root.getAsJsonArray("data")?.forEach { element ->
                    parseCard(element.asJsonObject)?.let(cards::add)
                }

                url = if (root.get("has_more")?.asBoolean == true) root.get("next_page")?.asString else null
                page++
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Failure("Lookup interrupted.")
        } catch (e: Exception) {
            logger.error("Scryfall fetch failed for query '$trimmed': $e")
            return Result.Failure("Couldn't reach Scryfall: ${e.message}")
        }

        if (cards.isEmpty()) return Result.Failure("No usable cards matched `$trimmed`.")
        return Result.Success(cards.take(maxCards))
    }

    /**
     * Resolves an explicit list of card names (e.g. a user's saved cube)
     * into cards via Scryfall's `/cards/collection` endpoint, batched at 75
     * identifiers per request. Returns the resolved cards (one per found
     * name, de-duplicated by the caller's list); names Scryfall doesn't know
     * are simply absent from the result. Creates a default client; the
     * overload taking one is the test seam.
     */
    fun fetchByNames(names: List<String>): Result = fetchByNames(names, HttpClients.createDefault())

    fun fetchByNames(names: List<String>, httpClient: HttpClient): Result {
        // Cap distinct lookups: a draft can't use more than DEFAULT_MAX_CARDS,
        // so resolving more would just spam Scryfall (one POST per 75 names)
        // and block the bot thread on the inter-batch delays.
        val unique = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(DEFAULT_MAX_CARDS)
        if (unique.isEmpty()) return Result.Failure("That cube has no card names in it.")

        val cards = mutableListOf<CubeCard>()
        try {
            unique.chunked(COLLECTION_BATCH).forEachIndexed { index, chunk ->
                if (index > 0) Thread.sleep(SCRYFALL_DELAY_MS) // be polite to the API
                val post = HttpPost(COLLECTION_ENDPOINT).apply {
                    setHeader("Content-Type", "application/json")
                    entity = StringEntity(collectionBody(chunk), StandardCharsets.UTF_8)
                }
                val response = httpClient.execute(post)
                val status = response.statusLine.statusCode
                if (status != 200) {
                    EntityUtils.consume(response.entity)
                    return Result.Failure("Scryfall returned HTTP $status resolving your cube.")
                }
                val root = JsonParser.parseReader(InputStreamReader(response.entity.content)).asJsonObject
                EntityUtils.consume(response.entity)
                root.getAsJsonArray("data")?.forEach { element ->
                    parseCard(element.asJsonObject)?.let(cards::add)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Failure("Lookup interrupted.")
        } catch (e: Exception) {
            logger.error("Scryfall collection lookup failed: $e")
            return Result.Failure("Couldn't reach Scryfall: ${e.message}")
        }

        if (cards.isEmpty()) return Result.Failure("None of those card names matched Scryfall.")
        return Result.Success(cards)
    }

    /** Builds the `{"identifiers":[{"name":...}]}` body, escaping names via Gson. */
    private fun collectionBody(names: List<String>): String {
        val identifiers = JsonArray()
        names.forEach { name ->
            identifiers.add(JsonObject().apply { addProperty("name", name) })
        }
        return JsonObject().apply { add("identifiers", identifiers) }.let(gson::toJson)
    }

    /**
     * Maps a single Scryfall card object to a [CubeCard]. Returns null for
     * entries without a name (which we can't meaningfully draft). Colour
     * comes from `color_identity` — the cube-sorting convention — and land
     * status from the type line.
     */
    fun parseCard(card: JsonObject): CubeCard? {
        val name = card.get("name")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val identity = card.getAsJsonArray("color_identity")
            ?.map { it.asString }
            ?: emptyList()
        val typeLine = card.get("type_line")?.asString ?: ""
        val manaValue = card.get("cmc")?.asDouble ?: 0.0
        return CubeCard(
            name = name,
            colors = MtgColor.parse(identity),
            isLand = CubeCard.isLandType(typeLine),
            typeLine = typeLine,
            manaValue = manaValue,
            imageUrl = imageUrl(card),
        )
    }

    /**
     * The card's `normal` image link, or null. Single-faced cards carry
     * `image_uris` directly; double-faced cards put it on the first face.
     */
    fun imageUrl(card: JsonObject): String? {
        fun normalOf(obj: JsonObject?): String? =
            obj?.getAsJsonObject("image_uris")?.get("normal")?.asString?.takeIf { it.isNotBlank() }

        normalOf(card)?.let { return it }
        val firstFace = card.getAsJsonArray("card_faces")?.firstOrNull()?.asJsonObject
        return normalOf(firstFace)
    }

    private fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return "$SEARCH_ENDPOINT?q=$encoded&unique=cards"
    }

    companion object {
        private const val SEARCH_ENDPOINT = "https://api.scryfall.com/cards/search"
        private const val COLLECTION_ENDPOINT = "https://api.scryfall.com/cards/collection"
        const val DEFAULT_MAX_CARDS = 750
        private const val MAX_PAGES = 10
        private const val COLLECTION_BATCH = 75
        private const val SCRYFALL_DELAY_MS = 100L
    }
}
