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
                    distribution = distribution(pool.value, packSize),
                )
            )
        }
    }

    /** Draws a cube from Scryfall and deals balanced packs. */
    fun generate(query: String, packCount: Int, packSize: Int, balanced: Boolean): CubeResult<GenerateData> {
        return when (val pool = fetchPool(query)) {
            is CubeResult.Failure -> CubeResult.error(pool.error)
            is CubeResult.Success -> {
                when (val packs = PackGenerator(Random.Default).generate(pool.value, packCount, packSize, balanced)) {
                    is PackGenerator.Result.Failure -> CubeResult.error(packs.reason)
                    is PackGenerator.Result.Success -> CubeResult.ok(
                        GenerateData(
                            query = query.trim(),
                            poolSize = pool.value.size,
                            packCount = packCount,
                            packSize = packSize,
                            balanced = balanced,
                            packs = packs.value.packs.map { pack -> pack.map { it.name } },
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

    /** Maps a Scryfall `/cards/search` JSON tree into cube cards. */
    fun parseCards(root: JsonNode): List<CubeCard> {
        val data = root.path("data")
        if (!data.isArray) return emptyList()
        return data.mapNotNull { node ->
            val name = node.path("name").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val identity = node.path("color_identity").mapNotNull { it.asText(null) }
            val typeLine = node.path("type_line").asText("")
            CubeCard(
                name = name,
                colors = MtgColor.parse(identity),
                isLand = typeLine.contains("Land", ignoreCase = true),
                typeLine = typeLine,
                manaValue = node.path("cmc").asDouble(0.0),
            )
        }
    }

    private fun fetchPool(query: String): CubeResult<List<CubeCard>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return CubeResult.error("Enter a Scryfall search query (e.g. set:vow).")

        val cards = mutableListOf<CubeCard>()
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
                cards.addAll(parseCards(root))
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

data class PreviewData(
    val query: String,
    val poolSize: Int,
    val packSize: Int,
    val distribution: List<CategoryAsFan>,
)

data class GenerateData(
    val query: String,
    val poolSize: Int,
    val packCount: Int,
    val packSize: Int,
    val balanced: Boolean,
    val packs: List<List<String>>,
    val distribution: List<CategoryAsFan>,
)
