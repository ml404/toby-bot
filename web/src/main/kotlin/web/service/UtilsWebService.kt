package web.service

import com.fasterxml.jackson.databind.ObjectMapper
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
    private val tokenSource: RedditTokenSource = RedditTokenProvider(),
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val jackson = ObjectMapper()

    fun randomMeme(subreddit: String, timePeriod: String, limit: Int): UtilsResult<MemeResult> {
        val sub = subreddit.trim()
        if (sub.isEmpty()) return UtilsResult.error("Subreddit is required.")
        // Reddit subreddit names are alphanumeric + underscore, 3-21 chars.
        // Validating here scrubs the taint before it reaches the URL sink.
        if (!SUBREDDIT_NAME.matches(sub)) {
            return UtilsResult.error("Invalid subreddit name.")
        }
        if (sub.equals("sneakybackgroundfeet", ignoreCase = true)) {
            return UtilsResult.error("Don't talk to me.")
        }
        val tp = validTimePeriod(timePeriod)
        val capped = limit.coerceIn(1, 100)

        // Reddit blocks the anonymous `.json` endpoint with a 403, so prefer the
        // app-only OAuth endpoint when credentials are configured (issue #403).
        val token = tokenSource.bearerToken()
        val url = when {
            token != null -> "https://oauth.reddit.com/r/$sub/top.json?limit=$capped&t=$tp&raw_json=1"
            tokenSource.isConfigured ->
                // Credentials are set but the token fetch failed (already logged) —
                // don't fall back to the blocked anonymous endpoint, surface a retry.
                return UtilsResult.error("Could not reach Reddit right now. Please try again later.")
            else -> "https://old.reddit.com/r/$sub/top/.json?limit=$capped&t=$tp"
        }

        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", RedditTokenProvider.USER_AGENT)
                    .apply { token?.let { header("Authorization", "bearer $it") } }
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

    private fun validTimePeriod(tp: String): String =
        when (tp.lowercase()) {
            "day", "week", "month", "all" -> tp.lowercase()
            else -> "day"
        }

    private companion object {
        val SUBREDDIT_NAME: Regex = Regex("^[A-Za-z0-9_]{1,50}$")
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
