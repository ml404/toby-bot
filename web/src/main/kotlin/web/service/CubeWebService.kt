package web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import common.mtg.AsFan
import common.mtg.CardCategory
import common.mtg.CubeCard
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
            is CubeResult.Success -> CubeResult.ok(
                PreviewData(
                    query = query.trim(),
                    poolSize = pool.value.size,
                    packSize = packSize,
                    groups = groups(pool.value, packSize),
                )
            )
        }
    }

    /** Draws a cube from Scryfall and deals balanced packs. */
    fun generate(query: String, packCount: Int, packSize: Int, balanced: Boolean): CubeResult<GenerateData> {
        return when (val pool = fetchPool(query)) {
            is CubeResult.Failure -> CubeResult.error(pool.error)
            is CubeResult.Success -> {
                val cards = pool.value.map { it.card }
                val viewByName = pool.value.associate { it.card.name to it.toView() }
                when (val packs = PackGenerator(Random.Default).generate(cards, packCount, packSize, balanced)) {
                    is PackGenerator.Result.Failure -> CubeResult.error(packs.reason)
                    is PackGenerator.Result.Success -> CubeResult.ok(
                        GenerateData(
                            query = query.trim(),
                            poolSize = pool.value.size,
                            packCount = packCount,
                            packSize = packSize,
                            balanced = balanced,
                            packs = packs.value.packs.map { pack ->
                                pack.map { viewByName[it.name] ?: CardView(it.name, null, null, it.typeLine, it.manaValue) }
                            },
                            distribution = distribution(packs.value.cards, packSize),
                        )
                    )
                }
            }
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
                    isLand = typeLine.contains("Land", ignoreCase = true),
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

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.scryfall.com/cards/search"
        const val MAX_CARDS = 750
        const val MAX_PAGES = 10
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

data class PreviewData(
    val query: String,
    val poolSize: Int,
    val packSize: Int,
    val groups: List<CategoryGroup>,
)

data class GenerateData(
    val query: String,
    val poolSize: Int,
    val packCount: Int,
    val packSize: Int,
    val balanced: Boolean,
    val packs: List<List<CardView>>,
    val distribution: List<CategoryAsFan>,
)
