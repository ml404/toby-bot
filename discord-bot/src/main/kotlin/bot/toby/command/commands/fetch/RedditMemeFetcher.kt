package bot.toby.command.commands.fetch

import bot.toby.dto.web.RedditAPIDto
import com.google.gson.Gson
import com.google.gson.JsonParser
import common.logging.DiscordLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * Pulls a random SFW post off a subreddit's top listing for `/meme`.
 *
 * Uses the same coroutine + ktor stack as the `/dnd` and `/mtgcube` lookups:
 * a `suspend` function that hops onto [Dispatchers.IO] via [withContext],
 * fed by the shared injectable ktor [HttpClient] with a per-request
 * timeout, so the blocking network work never ties up the CPU-bound
 * default dispatcher and a slow Reddit can't hang the calling thread.
 * Tests drive it with a `MockEngine`.
 */
@Component
class RedditMemeFetcher @Autowired constructor(
    private val client: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val gson: Gson = Gson()

    sealed interface Result {
        data class Success(val title: String, val url: String, val author: String) : Result
        data class Error(val message: String) : Result
    }

    /** Fetches a random non-NSFW, non-video post from the top [limit] of [subreddit] over [timePeriod]. */
    suspend fun fetch(subreddit: String?, timePeriod: String, limit: Int): Result = withContext(dispatcher) {
        val url = String.format(RedditAPIDto.REDDIT_PREFIX, subreddit, limit, timePeriod)
        logger.info("Fetching Reddit post from URL: $url")
        try {
            val response = client.get(url) {
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = TIMEOUT_MS }
            }
            if (response.status.value != 200) {
                logger.error("Error response from Reddit API: ${response.status.value}")
                return@withContext Result.Error("Failed to fetch meme. Please try again later.")
            }

            val json = JsonParser.parseString(response.bodyAsText()).asJsonObject
            val children = json.getAsJsonObject("data").getAsJsonArray("children")
            if (children.size() == 0) {
                return@withContext Result.Error("No memes found in r/${subreddit ?: "?"}.")
            }

            val meme = children[Random.nextInt(children.size())].asJsonObject.getAsJsonObject("data")
            val dto = gson.fromJson(meme.toString(), RedditAPIDto::class.java)
            if (dto.isNsfw == true) {
                logger.warn("NSFW meme detected from subreddit: $subreddit")
                return@withContext Result.Error(
                    "I pulled back a NSFW post — either the subreddit's flagged or Reddit picked a bad one. Skipped."
                )
            }
            if (dto.video == true) {
                logger.warn("Video meme detected from subreddit: $subreddit")
                return@withContext Result.Error("I pulled back a video, whoops. Try again maybe? Or not, up to you.")
            }

            Result.Success(
                title = meme["title"].asString,
                url = meme["url"].asString,
                author = meme["author"].asString,
            )
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            logger.error("Reddit fetch failed for subreddit '$subreddit': $e")
            Result.Error("Failed to fetch meme. Please try again later.")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
