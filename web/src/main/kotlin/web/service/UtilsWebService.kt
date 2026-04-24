package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import common.helpers.Cache
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.random.Random

@Service
class UtilsWebService(
    private val cache: Cache
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val jackson = ObjectMapper()

    fun randomMeme(subreddit: String, timePeriod: String, limit: Int): UtilsResult<MemeResult> {
        val sub = subreddit.trim()
        if (sub.isEmpty()) return UtilsResult.error("Subreddit is required.")
        if (sub.equals("sneakybackgroundfeet", ignoreCase = true)) {
            return UtilsResult.error("Don't talk to me.")
        }
        val tp = validTimePeriod(timePeriod)
        val capped = limit.coerceIn(1, 100)
        val url = "https://old.reddit.com/r/$sub/top/.json?limit=$capped&t=$tp"

        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "tobybot-web/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (response.statusCode() != 200) {
                return UtilsResult.error("Reddit returned ${response.statusCode()}.")
            }
            val root = jackson.readTree(response.body())
            val children = root.path("data").path("children")
            if (!children.isArray || children.isEmpty) {
                return UtilsResult.error("No memes found in r/$sub.")
            }
            val candidates = children
                .mapNotNull { it.path("data").takeIf { d -> d.isObject } }
                .filter { it.path("over_18").asBoolean(false).not() }
                .filter { it.path("is_video").asBoolean(false).not() }
            if (candidates.isEmpty()) {
                return UtilsResult.error("No SFW image posts found in r/$sub.")
            }
            val picked = candidates[Random.nextInt(candidates.size)]
            UtilsResult.ok(
                MemeResult(
                    title = picked.path("title").asText(""),
                    author = picked.path("author").asText(""),
                    imageUrl = picked.path("url_overridden_by_dest").asText(picked.path("url").asText("")),
                    permalink = "https://reddit.com" + picked.path("permalink").asText(""),
                    subreddit = sub
                )
            )
        } catch (e: IOException) {
            UtilsResult.error("Could not reach Reddit: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            UtilsResult.error("Request interrupted.")
        }
    }

    fun randomDbdKiller(): UtilsResult<String> {
        return try {
            val killers = fetchWiki(
                cacheKey = "dbdKillers",
                url = "https://deadbydaylight.fandom.com/wiki/Killers",
                className = "mw-content-ltr"
            ) { root -> parseDbdKillers(root) }
            if (killers.isEmpty()) UtilsResult.error("No killers parsed.")
            else UtilsResult.ok(killers[Random.nextInt(killers.size)])
        } catch (e: IOException) {
            UtilsResult.error("Could not reach the wiki: ${e.message}")
        }
    }

    fun randomKf2Map(): UtilsResult<String> {
        return try {
            val maps = fetchWiki(
                cacheKey = "kf2Maps",
                url = "https://wiki.killingfloor2.com/index.php?title=Maps_(Killing_Floor_2)",
                className = "mw-parser-output"
            ) { root -> root.select("b").eachText() }
            if (maps.isEmpty()) UtilsResult.error("No maps parsed.")
            else UtilsResult.ok(maps[Random.nextInt(maps.size)])
        } catch (e: IOException) {
            UtilsResult.error("Could not reach the wiki: ${e.message}")
        }
    }

    private fun fetchWiki(
        cacheKey: String,
        url: String,
        className: String,
        parse: (Element) -> List<String>
    ): List<String> {
        cache.get(cacheKey)?.let { return it }
        val doc = Jsoup.connect(url).get()
        val root = doc.getElementsByClass(className).first()
            ?: throw IOException("Element with class '$className' not found")
        val values = parse(root).filter { it.isNotBlank() }
        cache.put(cacheKey, values)
        return values
    }

    private fun parseDbdKillers(root: Element): List<String> {
        val outerDivs = root.select("div")
        val container = outerDivs.getOrNull(3) ?: return emptyList()
        return container.select("> div").mapNotNull { div ->
            val realName = div.selectFirst("a")?.text()?.trim()
            val alias = div.ownText().trim()
            when {
                !realName.isNullOrBlank() && alias.isNotBlank() -> "$realName — $alias"
                !realName.isNullOrBlank() -> realName
                alias.isNotBlank() -> alias
                else -> null
            }
        }
    }

    private fun validTimePeriod(tp: String): String =
        when (tp.lowercase()) {
            "day", "week", "month", "all" -> tp.lowercase()
            else -> "day"
        }
}

data class UtilsResult<T>(val value: T?, val error: String?) {
    companion object {
        fun <T> ok(value: T) = UtilsResult(value, null)
        fun <T> error(message: String) = UtilsResult<T>(null, message)
    }
}

data class MemeResult(
    val title: String,
    val author: String,
    val imageUrl: String,
    val permalink: String,
    val subreddit: String
)
