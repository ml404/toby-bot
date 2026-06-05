package web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import common.mtg.AsFan
import common.mtg.CardCategory
import common.mtg.CardListParser
import common.mtg.CubeCard
import common.mtg.MtgNames
import common.mtg.MtgColor
import common.mtg.PackGenerator
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.random.Random

/**
 * Web-surface twin of the bot's cube tooling. Same `common.mtg` maths and
 * pack generation, fed by the Scryfall search API over `java.net.http`
 * (matching [UtilsWebService]'s HTTP style) so the Discord command and the
 * web page can't drift on as-fan numbers or pack-dealing rules.
 */
@Service
class CubeWebService {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val jackson = ObjectMapper()

    /** The as-fan calculator: (type ÷ cube) × pack size. */
    fun asFan(total: Int, cubeSize: Int, packSize: Int): CubeResult<Double> =
        try {
            CubeResult.ok(AsFan.value(total, cubeSize, packSize))
        } catch (e: IllegalArgumentException) {
            CubeResult.error(e.message ?: "Invalid as-fan inputs.")
        }

    /** As-fan distribution of a Scryfall query's cards, no pack generation. */
    fun preview(query: String, packSize: Int): CubeResult<PreviewData> {
        if (packSize <= 0) return CubeResult.error("Pack size must be at least 1.")
        return when (val pool = fetchPool(query)) {
            is CubeResult.Failure -> CubeResult.error(pool.error)
            is CubeResult.Success -> CubeResult.ok(buildPreview(query.trim(), pool.value, packSize, emptyList()))
        }
    }

    /**
     * Like [preview] but the pool is the user's own pasted card list,
     * resolved against Scryfall, rather than a search query.
     */
    fun previewList(list: String, packSize: Int): CubeResult<PreviewData> {
        if (packSize <= 0) return CubeResult.error("Pack size must be at least 1.")
        return when (val resolved = resolveList(list)) {
            is CubeResult.Failure -> CubeResult.error(resolved.error)
            is CubeResult.Success -> CubeResult.ok(
                buildPreview("your list", resolved.value.cards, packSize, resolved.value.notFound, resolved.value.note)
            )
        }
    }

    /** Draws a cube from a Scryfall query and deals balanced packs. */
    fun generate(query: String, packCount: Int, packSize: Int, balanced: Boolean): CubeResult<GenerateData> =
        when (val pool = fetchPool(query)) {
            is CubeResult.Failure -> CubeResult.error(pool.error)
            is CubeResult.Success -> buildGenerate(query.trim(), pool.value, packCount, packSize, balanced, emptyList())
        }

    /** Like [generate] but deals from the user's own pasted card list. */
    fun generateList(list: String, packCount: Int, packSize: Int, balanced: Boolean): CubeResult<GenerateData> =
        when (val resolved = resolveList(list)) {
            is CubeResult.Failure -> CubeResult.error(resolved.error)
            is CubeResult.Success ->
                buildGenerate("your list", resolved.value.cards, packCount, packSize, balanced, resolved.value.notFound, resolved.value.note)
        }

    private fun buildPreview(
        label: String,
        pool: List<ScryfallCard>,
        packSize: Int,
        notFound: List<String>,
        note: String? = null,
    ): PreviewData = PreviewData(
        query = label,
        poolSize = pool.size,
        packSize = packSize,
        groups = groups(pool, packSize),
        notFound = notFound,
        note = note,
    )

    private fun buildGenerate(
        label: String,
        pool: List<ScryfallCard>,
        packCount: Int,
        packSize: Int,
        balanced: Boolean,
        notFound: List<String>,
        note: String? = null,
    ): CubeResult<GenerateData> {
        val cards = pool.map { it.card }
        val viewByName = pool.associate { it.card.name to it.toView() }
        return when (val packs = PackGenerator(Random.Default).generate(cards, packCount, packSize, balanced)) {
            is PackGenerator.Result.Failure -> CubeResult.error(packs.reason)
            is PackGenerator.Result.Success -> CubeResult.ok(
                GenerateData(
                    query = label,
                    poolSize = pool.size,
                    packCount = packCount,
                    packSize = packSize,
                    balanced = balanced,
                    packs = packs.value.packs.map { pack ->
                        pack.map { viewByName[it.name] ?: CardView(it.name, null, null, it.typeLine, it.manaValue) }
                    },
                    distribution = distribution(packs.value.cards, packSize),
                    notFound = notFound,
                    note = note,
                )
            )
        }
    }

    /** Builds an ordered, present-only category as-fan table. */
    fun distribution(pool: List<CubeCard>, packSize: Int): List<CategoryAsFan> {
        val counts = AsFan.categoryCounts(pool)
        val asFans = AsFan.distribution(pool, packSize)
        return CardCategory.entries
            .filter { counts[it] != null }
            .map { cat ->
                CategoryAsFan(cat.displayName, counts.getValue(cat), asFans.getValue(cat))
            }
    }

    /**
     * Like [distribution] but carries the actual cards (name + thumbnail)
     * in each bucket, alphabetised. Cards dedupe by name so a pool with
     * duplicates doesn't repeat one in the list; [CategoryGroup.count]
     * still reflects the raw bucket size.
     */
    fun groups(pool: List<ScryfallCard>, packSize: Int): List<CategoryGroup> {
        val asFans = AsFan.distribution(pool.map { it.card }, packSize)
        val byCategory = pool.groupBy { it.card.category }
        return CardCategory.entries
            .filter { byCategory[it] != null }
            .map { cat ->
                val bucket = byCategory.getValue(cat)
                val cards = bucket
                    .distinctBy { it.card.name }
                    .sortedBy { it.card.name }
                    .map { it.toView() }
                CategoryGroup(
                    category = cat.displayName,
                    count = bucket.size,
                    asFan = asFans.getValue(cat),
                    cards = cards,
                )
            }
    }

    /** Maps a Scryfall `/cards/search` JSON tree into cube cards. */
    fun parseCards(root: JsonNode): List<CubeCard> = parseScryfall(root).map { it.card }

    /**
     * Like [parseCards] but keeps each card's small thumbnail URL so the
     * web page can render images. Single-faced cards carry `image_uris`
     * directly; double-faced cards put images on the first face instead.
     */
    fun parseScryfall(root: JsonNode): List<ScryfallCard> {
        val data = root.path("data")
        if (!data.isArray) return emptyList()
        return data.mapNotNull { node ->
            val name = node.path("name").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val identity = node.path("color_identity").mapNotNull { it.asText(null) }
            val typeLine = node.path("type_line").asText("")
            ScryfallCard(
                card = CubeCard(
                    name = name,
                    colors = MtgColor.parse(identity),
                    isLand = CubeCard.isLandType(typeLine),
                    typeLine = typeLine,
                    manaValue = node.path("cmc").asDouble(0.0),
                ),
                imageUrl = imageOf(node, "small"),
                imageUrlLarge = imageOf(node, "normal"),
            )
        }
    }

    /**
     * A card image at the given Scryfall [size] (`small` ≈ 146×204 thumb,
     * `normal` ≈ 488×680 for the hover zoom), or null if Scryfall has none.
     * Single-faced cards carry `image_uris` directly; double-faced cards
     * put images on the first face instead.
     */
    private fun imageOf(node: JsonNode, size: String): String? {
        node.path("image_uris").path(size).asText("").takeIf { it.isNotBlank() }?.let { return it }
        val faces = node.path("card_faces")
        if (faces.isArray && faces.size() > 0) {
            faces[0].path("image_uris").path(size).asText("").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun fetchPool(query: String): CubeResult<List<ScryfallCard>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return CubeResult.error("Enter a Scryfall search query (e.g. set:vow).")

        val cards = mutableListOf<ScryfallCard>()
        var url: String? = searchUrl(trimmed)
        var page = 0
        try {
            while (url != null && cards.size < MAX_CARDS && page < MAX_PAGES) {
                val response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "tobybot-web/1.0")
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                when (response.statusCode()) {
                    200 -> {}
                    404 -> return CubeResult.error("No cards matched that query.")
                    else -> return CubeResult.error("Scryfall returned ${response.statusCode()}.")
                }
                val root = jackson.readTree(response.body())
                cards.addAll(parseScryfall(root))
                url = if (root.path("has_more").asBoolean(false)) {
                    root.path("next_page").asText(null)
                } else {
                    null
                }
                page++
            }
        } catch (e: IOException) {
            return CubeResult.error("Could not reach Scryfall: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return CubeResult.error("Request interrupted.")
        }

        if (cards.isEmpty()) return CubeResult.error("No usable cards matched that query.")
        return CubeResult.ok(cards.take(MAX_CARDS))
    }

    private fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return "$SEARCH_ENDPOINT?q=$encoded&unique=cards"
    }

    /**
     * Parses a pasted decklist into (name, count) entries. Delegates to the
     * shared [CardListParser] so the web tool and the Discord command agree
     * on the accepted format.
     */
    fun parseList(text: String): List<ListEntry> =
        CardListParser.parse(text).map { ListEntry(it.name, it.count) }

    /**
     * Resolves a pasted list into a card pool by looking the names up via
     * Scryfall's `/cards/collection` endpoint (batched, ≤75 per request).
     * Quantities expand the pool (`3 Forest` → three Forests); names that
     * don't resolve are reported in [ResolvedPool.notFound].
     */
    private fun resolveList(text: String): CubeResult<ResolvedPool> {
        val entries = parseList(text)
        if (entries.isEmpty()) return CubeResult.error("Paste at least one card name, one per line.")

        val fetched = mutableListOf<ScryfallCard>()
        // Look cards up by their front face: Scryfall's collection lookup
        // matches a single face, not the full "A // B" name. matchEntries
        // ties the full-name cards Scryfall returns back to the entries.
        // Cap how many distinct names we look up: the pool is capped at
        // MAX_CARDS anyway, so resolving more would just hammer Scryfall (one
        // POST per 75 names) and tie up the request thread for no benefit.
        // Names past the cap fall through to notFound.
        val requestNames = entries.map { MtgNames.requestName(it.name) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CARDS)
        for (chunk in requestNames.chunked(COLLECTION_BATCH)) {
            when (val batch = fetchCollection(chunk)) {
                is CubeResult.Failure -> return CubeResult.error(batch.error)
                is CubeResult.Success -> fetched.addAll(batch.value)
            }
        }

        val matched = matchEntries(entries, fetched)
        if (matched.pool.isEmpty()) return CubeResult.error("None of those card names matched Scryfall. Check the spelling?")
        // Be transparent if we had to cap a very large pool rather than
        // silently dropping the overflow.
        val note = if (matched.pool.size > MAX_CARDS) {
            "Your list resolved to ${matched.pool.size} cards; only the first $MAX_CARDS were used."
        } else {
            null
        }
        return CubeResult.ok(ResolvedPool(matched.pool.take(MAX_CARDS), matched.notFound, note))
    }

    /**
     * Matches parsed list entries to fetched cards and expands quantities.
     * Each card is indexed by its full name AND each face, so a pasted
     * front-face name (e.g. "Archangel Avacyn") matches the full name
     * Scryfall returns for a transform card ("Archangel Avacyn // Avacyn,
     * the Purifier"). Entries that don't resolve go into [MatchResult.notFound].
     */
    fun matchEntries(entries: List<ListEntry>, cards: List<ScryfallCard>): MatchResult {
        val byKey = HashMap<String, ScryfallCard>()
        cards.forEach { card ->
            MtgNames.matchKeys(card.card.name).forEach { key -> byKey.putIfAbsent(key, card) }
        }
        val pool = mutableListOf<ScryfallCard>()
        val notFound = mutableListOf<String>()
        for (entry in entries) {
            val card = byKey[MtgNames.lookupKey(entry.name)]
            if (card == null) notFound.add(entry.name) else repeat(entry.count) { pool.add(card) }
        }
        return MatchResult(pool, notFound.distinct())
    }

    /** One `/cards/collection` POST for up to 75 names. */
    private fun fetchCollection(names: List<String>): CubeResult<List<ScryfallCard>> {
        val identifiers = names.joinToString(",") { """{"name":${jackson.writeValueAsString(it)}}""" }
        val body = """{"identifiers":[$identifiers]}"""
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(COLLECTION_ENDPOINT))
                    .header("User-Agent", "tobybot-web/1.0")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() != 200) {
                return CubeResult.error("Scryfall returned ${response.statusCode()} resolving your list.")
            }
            CubeResult.ok(parseScryfall(jackson.readTree(response.body())))
        } catch (e: IOException) {
            CubeResult.error("Could not reach Scryfall: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CubeResult.error("Request interrupted.")
        }
    }

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.scryfall.com/cards/search"
        const val COLLECTION_ENDPOINT = "https://api.scryfall.com/cards/collection"
        const val MAX_CARDS = 750
        const val MAX_PAGES = 10
        const val COLLECTION_BATCH = 75
    }
}

sealed interface CubeResult<out T> {
    data class Success<T>(val value: T) : CubeResult<T>
    data class Failure(val error: String) : CubeResult<Nothing>

    companion object {
        fun <T> ok(value: T): CubeResult<T> = Success(value)
        fun error(message: String): CubeResult<Nothing> = Failure(message)
    }
}

data class CategoryAsFan(val category: String, val count: Int, val asFan: Double)

/** A card paired with its Scryfall images, kept only inside the service. */
data class ScryfallCard(val card: CubeCard, val imageUrl: String?, val imageUrlLarge: String?) {
    fun toView(): CardView = CardView(card.name, imageUrl, imageUrlLarge, card.typeLine, card.manaValue)
}

/**
 * A single card the page renders: display name, small thumbnail, the larger
 * image used for the hover-to-enlarge preview, and the stat line (type +
 * mana value) shown under that preview. Either image may be null when
 * Scryfall has none.
 */
data class CardView(
    val name: String,
    val imageUrl: String?,
    val imageUrlLarge: String?,
    val typeLine: String,
    val manaValue: Double,
)

/** A colour/land bucket plus the actual cards it contains. */
data class CategoryGroup(
    val category: String,
    val count: Int,
    val asFan: Double,
    val cards: List<CardView>,
)

/** One parsed decklist line: a card name and how many copies to include. */
data class ListEntry(val name: String, val count: Int)

/** A resolved custom list: the card pool, unresolved names, and an optional note. */
data class ResolvedPool(
    val cards: List<ScryfallCard>,
    val notFound: List<String>,
    val note: String? = null,
)

/** Outcome of matching list entries to fetched cards: the pool + unresolved names. */
data class MatchResult(val pool: List<ScryfallCard>, val notFound: List<String>)

data class PreviewData(
    val query: String,
    val poolSize: Int,
    val packSize: Int,
    val groups: List<CategoryGroup>,
    val notFound: List<String> = emptyList(),
    val note: String? = null,
)

data class GenerateData(
    val query: String,
    val poolSize: Int,
    val packCount: Int,
    val packSize: Int,
    val balanced: Boolean,
    val packs: List<List<CardView>>,
    val distribution: List<CategoryAsFan>,
    val notFound: List<String> = emptyList(),
    val note: String? = null,
)
