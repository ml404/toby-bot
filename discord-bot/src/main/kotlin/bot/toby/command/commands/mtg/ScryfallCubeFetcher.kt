package bot.toby.command.commands.mtg

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import common.logging.DiscordLogger
import common.mtg.CardCombos
import common.mtg.CardRulings
import common.mtg.CubeCard
import common.mtg.MtgColor
import common.mtg.MtgSet
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pulls a cube's card pool from the public Scryfall search API
 * (`/cards/search`). A "cube" here is just whatever a Scryfall query
 * matches — e.g. `set:vow`, `is:commander`, `t:dragon`, or a curated
 * `cube:<name>` — so a user defines their pool with the same search
 * syntax they'd use on scryfall.com.
 *
 * Uses the same coroutine + ktor stack as the `/dnd` lookups
 * ([bot.toby.helpers.HttpHelper]): `suspend` functions that hop onto
 * [Dispatchers.IO] via [withContext], fed by the shared injectable ktor
 * [HttpClient] (so blocking network work never ties up the CPU-bound
 * default dispatcher, and tests can drive it with a `MockEngine`). The
 * card → [CubeCard] mapping is split into [parseCard] so it can be
 * unit-tested without any network.
 */
@Component
class ScryfallCubeFetcher @Autowired constructor(
    private val client: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val gson: Gson = Gson()

    sealed interface Result {
        /**
         * [capped] is true when the pool was truncated to the [DEFAULT_MAX_CARDS]
         * ceiling — i.e. the query matched (or the list named) more cards than
         * we deal from — so callers can tell the user rather than silently
         * dropping the overflow.
         */
        data class Success(val cards: List<CubeCard>, val capped: Boolean = false) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Fetches up to [maxCards] cards matching [query], following Scryfall's
     * pagination. Suspends on [Dispatchers.IO]; the pages are inherently
     * sequential (each `next_page` comes from the previous response), which
     * also naturally spaces the requests.
     */
    suspend fun fetch(query: String, maxCards: Int = DEFAULT_MAX_CARDS): Result = withContext(dispatcher) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.Failure("Give me a Scryfall search query (e.g. `set:vow`).")

        val cards = mutableListOf<CubeCard>()
        var url: String? = searchUrl(trimmed)
        var page = 0

        try {
            while (url != null && cards.size < maxCards && page < MAX_PAGES) {
                val response = client.get(url) {
                    header(HttpHeaders.Accept, "application/json")
                    timeout { requestTimeoutMillis = TIMEOUT_MS }
                }
                val status = response.status.value
                if (status == 404) return@withContext Result.Failure("No cards matched `$trimmed`.")
                if (status != 200) return@withContext Result.Failure("Scryfall returned HTTP $status. Try again later.")

                val root = JsonParser.parseString(response.bodyAsText()).asJsonObject
                root.getAsJsonArray("data")?.forEach { element ->
                    parseCard(element.asJsonObject)?.let(cards::add)
                }
                url = if (root.get("has_more")?.asBoolean == true) root.get("next_page")?.asString else null
                page++
            }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            logger.error("Scryfall fetch failed for query '$trimmed': $e")
            return@withContext Result.Failure("Couldn't reach Scryfall: ${e.message}")
        }

        if (cards.isEmpty()) return@withContext Result.Failure("No usable cards matched `$trimmed`.")
        // The query matched more than we deal from if we overshot the ceiling
        // on the last page, or stopped with pages still unfetched.
        val capped = cards.size > maxCards || url != null
        Result.Success(cards.take(maxCards), capped)
    }

    /**
     * Resolves an explicit list of card names (e.g. a user's saved cube)
     * into cards via Scryfall's `/cards/collection` endpoint, batched at 75
     * identifiers per request. The batches run concurrently (bounded by
     * [MAX_CONCURRENT_BATCHES] so we don't burst past Scryfall's rate limit)
     * on [Dispatchers.IO]. Names Scryfall doesn't know are simply absent from
     * the result; the caller ties the returned cards back to its list.
     */
    suspend fun fetchByNames(names: List<String>): Result = withContext(dispatcher) {
        // Cap distinct lookups: a draft can't use more than DEFAULT_MAX_CARDS,
        // so resolving more would just spam Scryfall (one POST per 75 names).
        val distinctNames = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val unique = distinctNames.take(DEFAULT_MAX_CARDS)
        if (unique.isEmpty()) return@withContext Result.Failure("That cube has no card names in it.")

        val gate = Semaphore(MAX_CONCURRENT_BATCHES)
        val batches = try {
            coroutineScope {
                unique.chunked(COLLECTION_BATCH)
                    .map { chunk -> async { gate.withPermit { fetchCollectionBatch(chunk) } } }
                    .awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Scryfall collection lookup failed: $e")
            return@withContext Result.Failure("Couldn't reach Scryfall: ${e.message}")
        }

        val cards = mutableListOf<CubeCard>()
        for (batch in batches) {
            when (batch) {
                is BatchResult.Failure -> return@withContext Result.Failure(batch.message)
                is BatchResult.Success -> cards.addAll(batch.cards)
            }
        }

        if (cards.isEmpty()) return@withContext Result.Failure("None of those card names matched Scryfall.")
        Result.Success(cards, capped = distinctNames.size > DEFAULT_MAX_CARDS)
    }

    private sealed interface BatchResult {
        data class Success(val cards: List<CubeCard>) : BatchResult
        data class Failure(val message: String) : BatchResult
    }

    /**
     * Resolves a single card by (fuzzy) name via Scryfall's `/cards/named`,
     * for inline `[[card]]` chat lookups. Returns null when nothing matches
     * or Scryfall is unreachable — the caller just skips it.
     */
    suspend fun fetchNamed(name: String): CubeCard? = withContext(dispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        val url = "$NAMED_ENDPOINT?fuzzy=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        try {
            val response = client.get(url) {
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = TIMEOUT_MS }
            }
            if (response.status.value != 200) return@withContext null
            parseCard(JsonParser.parseString(response.bodyAsText()).asJsonObject)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Scryfall named lookup failed for '$trimmed': $e")
            null
        }
    }

    /**
     * Resolves a card by (fuzzy) name and fetches its official rulings in two
     * steps: `/cards/named?fuzzy=` to find the card (and its `rulings_uri`),
     * then a GET of that uri. Returns null when no card matches or Scryfall is
     * unreachable; a [CardRulings] with an empty list when the card exists but
     * has no published rulings. Suspends on [Dispatchers.IO].
     */
    suspend fun fetchRulings(name: String): CardRulings? = withContext(dispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        val namedUrl = "$NAMED_ENDPOINT?fuzzy=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        try {
            val cardResp = client.get(namedUrl) {
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = TIMEOUT_MS }
            }
            if (cardResp.status.value != 200) return@withContext null
            val card = JsonParser.parseString(cardResp.bodyAsText()).asJsonObject
            val cardName = card.get("name")?.asString?.takeIf { it.isNotBlank() } ?: return@withContext null
            val scryfallUri = card.get("scryfall_uri")?.asString
            val rulingsUri = card.get("rulings_uri")?.asString
                ?: return@withContext CardRulings(cardName, scryfallUri, emptyList())

            val rulingsResp = client.get(rulingsUri) {
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = TIMEOUT_MS }
            }
            val rulings = if (rulingsResp.status.value == 200) {
                parseRulings(JsonParser.parseString(rulingsResp.bodyAsText()).asJsonObject)
            } else {
                emptyList()
            }
            CardRulings(cardName, scryfallUri, rulings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Scryfall rulings lookup failed for '$trimmed': $e")
            null
        }
    }

    /** Maps a Scryfall `/rulings` JSON tree into [CardRulings.Ruling]s, oldest first as returned. */
    fun parseRulings(root: JsonObject): List<CardRulings.Ruling> =
        root.getAsJsonArray("data")?.mapNotNull { element ->
            val obj = element.asJsonObject
            val comment = obj.get("comment")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CardRulings.Ruling(obj.get("published_at")?.asString.orEmpty(), comment)
        } ?: emptyList()

    /**
     * Finds the combos a card appears in via the Commander Spellbook variants
     * API, capped at [MAX_COMBOS]. Returns null when the lookup fails or the
     * card name is blank; an empty [CardCombos.combos] list when the card is in
     * no known combos. Suspends on [Dispatchers.IO].
     */
    suspend fun fetchCombos(name: String): CardCombos? = withContext(dispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        // q=card:"Name" filters variants that use that exact card.
        val query = URLEncoder.encode("card:\"$trimmed\"", StandardCharsets.UTF_8)
        val url = "$SPELLBOOK_ENDPOINT?q=$query&limit=$MAX_COMBOS&ordering=-popularity"
        try {
            val response = client.get(url) {
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = TIMEOUT_MS }
            }
            if (response.status.value != 200) return@withContext null
            CardCombos(trimmed, parseCombos(JsonParser.parseString(response.bodyAsText()).asJsonObject))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Commander Spellbook lookup failed for '$trimmed': $e")
            null
        }
    }

    /**
     * Looks up a Magic set by its code (e.g. "vow") via Scryfall's `/sets/:code`.
     * Returns null when the code is unknown, blank, or Scryfall is unreachable.
     */
    suspend fun fetchSet(code: String): MtgSet? = withContext(dispatcher) {
        val trimmed = code.trim().lowercase()
        if (trimmed.isEmpty()) return@withContext null
        val url = "$SETS_ENDPOINT/" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        try {
            val response = client.get(url) {
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = TIMEOUT_MS }
            }
            if (response.status.value != 200) return@withContext null
            parseSet(JsonParser.parseString(response.bodyAsText()).asJsonObject)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Scryfall set lookup failed for '$trimmed': $e")
            null
        }
    }

    /** Maps a Scryfall set object to [MtgSet], or null without a code/name. */
    fun parseSet(set: JsonObject): MtgSet? {
        val code = set.get("code")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val name = set.get("name")?.asString?.takeIf { it.isNotBlank() } ?: return null
        return MtgSet(
            code = code.uppercase(),
            name = name,
            setType = set.get("set_type")?.asString?.replace('_', ' ') ?: "",
            releasedAt = set.get("released_at")?.asString?.takeIf { it.isNotBlank() },
            cardCount = set.get("card_count")?.asInt ?: 0,
            iconUrl = set.get("icon_svg_uri")?.asString?.takeIf { it.isNotBlank() },
            scryfallUri = set.get("scryfall_uri")?.asString?.takeIf { it.isNotBlank() },
        )
    }

    /** Maps a Commander Spellbook variants response into [CardCombos.Combo]s. */
    fun parseCombos(root: JsonObject): List<CardCombos.Combo> =
        root.getAsJsonArray("results")?.mapNotNull { element ->
            val obj = element.asJsonObject
            val id = obj.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val uses = obj.getAsJsonArray("uses")?.mapNotNull { use ->
                use.asJsonObject.getAsJsonObject("card")?.get("name")?.asString?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val produces = obj.getAsJsonArray("produces")?.mapNotNull { prod ->
                prod.asJsonObject.getAsJsonObject("feature")?.get("name")?.asString?.takeIf { it.isNotBlank() }
            }.orEmpty()
            // A combo with no listed pieces or payoff isn't worth showing.
            if (uses.isEmpty() && produces.isEmpty()) return@mapNotNull null
            CardCombos.Combo(id, uses, produces, CardCombos.comboUrl(id))
        }.orEmpty()

    /** One `/cards/collection` POST for up to 75 names. */
    private suspend fun fetchCollectionBatch(chunk: List<String>): BatchResult {
        val response: HttpResponse = client.post(COLLECTION_ENDPOINT) {
            header(HttpHeaders.Accept, "application/json")
            timeout { requestTimeoutMillis = TIMEOUT_MS }
            setBody(TextContent(collectionBody(chunk), ContentType.Application.Json))
        }
        val status = response.status.value
        if (status != 200) return BatchResult.Failure("Scryfall returned HTTP $status resolving your cube.")
        val root = JsonParser.parseString(response.bodyAsText()).asJsonObject
        val cards = mutableListOf<CubeCard>()
        root.getAsJsonArray("data")?.forEach { element ->
            parseCard(element.asJsonObject)?.let(cards::add)
        }
        return BatchResult.Success(cards)
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
            rarity = card.get("rarity")?.asString?.takeIf { it.isNotBlank() },
            manaCost = manaCost(card),
            oracleText = oracleText(card),
            imageUrlBack = backImageUrl(card),
            priceUsd = price(card, "usd"),
            priceEur = price(card, "eur"),
            priceTix = price(card, "tix"),
            legalFormats = CubeCard.legalFormatsOf { fmt -> legality(card, fmt) },
            legalities = CubeCard.legalitiesOf { fmt -> legality(card, fmt) },
        )
    }

    /** A Scryfall `legalities` status for a format code, or null when absent. */
    fun legality(card: JsonObject, format: String): String? =
        card.getAsJsonObject("legalities")?.get(format)?.takeIf { !it.isJsonNull }?.asString

    /** A Scryfall `prices` entry (e.g. "usd"), or null when absent/blank. */
    fun price(card: JsonObject, key: String): String? =
        card.getAsJsonObject("prices")?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    /**
     * The card's rules text, or null. Single-faced cards carry `oracle_text`
     * directly; double-faced cards put it on each face, so both are combined
     * with their face names.
     */
    fun oracleText(card: JsonObject): String? {
        card.get("oracle_text")?.asString?.takeIf { it.isNotBlank() }?.let { return it }
        val faces = card.getAsJsonArray("card_faces") ?: return null
        val parts = faces.mapNotNull { face ->
            val obj = face.asJsonObject
            val text = obj.get("oracle_text")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            obj.get("name")?.asString?.let { "**$it**\n$text" } ?: text
        }
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    /** The back-face `normal` image for a double-faced card, or null. */
    fun backImageUrl(card: JsonObject): String? {
        val faces = card.getAsJsonArray("card_faces") ?: return null
        if (faces.size() < 2) return null
        return faces[1].asJsonObject.getAsJsonObject("image_uris")?.get("normal")?.asString?.takeIf { it.isNotBlank() }
    }

    /**
     * The card's `mana_cost`, or null. Single-faced cards carry it directly;
     * double-faced cards put it on the first face.
     */
    fun manaCost(card: JsonObject): String? {
        fun costOf(obj: JsonObject?): String? =
            obj?.get("mana_cost")?.asString?.takeIf { it.isNotBlank() }

        costOf(card)?.let { return it }
        val firstFace = card.getAsJsonArray("card_faces")?.firstOrNull()?.asJsonObject
        return costOf(firstFace)
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
        private const val NAMED_ENDPOINT = "https://api.scryfall.com/cards/named"
        private const val SETS_ENDPOINT = "https://api.scryfall.com/sets"
        private const val SPELLBOOK_ENDPOINT = "https://backend.commanderspellbook.com/variants/"

        /** Cap on how many combos a single `/mtgcard combos` lookup returns. */
        const val MAX_COMBOS = 5
        const val DEFAULT_MAX_CARDS = 750
        private const val MAX_PAGES = 10
        private const val COLLECTION_BATCH = 75

        /** Bounds the concurrent `/cards/collection` POSTs to stay polite to Scryfall. */
        private const val MAX_CONCURRENT_BATCHES = 3
        private const val TIMEOUT_MS = 10_000L
    }
}
