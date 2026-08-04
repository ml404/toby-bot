package bot.toby.helpers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HttpHelper(
    private val client: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val youtubeApiKey: String? = System.getenv("YOUTUBE_API_KEY"),
    // Injectable so tests can exercise the timeout without spending the real
    // budget on every run.
    private val youtubeRequestTimeoutMs: Long = YOUTUBE_REQUEST_TIMEOUT_MS,
    private val youtubeConnectTimeoutMs: Long = YOUTUBE_CONNECT_TIMEOUT_MS,
) {

    suspend fun fetchFromGet(url: String?): String = withContext(dispatcher) {
        if (url.isNullOrBlank()) return@withContext ""

        return@withContext try {
            val response: HttpResponse = client.get(url) {
                headers {
                    append(HttpHeaders.Accept, "application/json")
                }
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<String>()
            } else {
                ""
            }
        } catch (e: Exception) {
            throw RuntimeException("HTTP error occurred", e)
        }
    }

    /**
     * How long the source is, for the intro length rule.
     *
     * Bounded, because a caller waits on it: setting an intro blocks on this
     * lookup, and the shared client installs `HttpTimeout` without configuring
     * one, leaving each call site to opt in. This one never did — so a
     * googleapis request that *hung* rather than failed left the user's
     * deferred reply unanswered until Discord gave up on the interaction, with
     * nothing saved and nothing said. A timeout turns that into an ordinary
     * "duration unknown", which the clip rules already know how to handle.
     */
    suspend fun getYouTubeVideoDuration(youtubeUrl: String): Duration? = withContext(dispatcher) {
        val videoId = extractVideoIdFromUrl(youtubeUrl) ?: return@withContext null
        val apiUrl = "https://www.googleapis.com/youtube/v3/videos?id=$videoId&part=contentDetails&key=$youtubeApiKey"
        val response: HttpResponse = client.get(apiUrl) { youtubeTimeouts() }
        val videoResponse: YouTubeVideoResponse = response.body()

        val durationIso = videoResponse.items?.firstOrNull()?.contentDetails?.duration ?: return@withContext null
        return@withContext parseIso8601Duration(durationIso) // Return Duration
    }

    /** The video's title, used to name an intro. Bounded for the same reason. */
    suspend fun getYouTubeVideoTitle(youtubeUrl: String): String? = withContext(dispatcher) {
        val videoId = extractVideoIdFromUrl(youtubeUrl) ?: return@withContext null
        val apiUrl = "https://www.googleapis.com/youtube/v3/videos?id=$videoId&part=snippet&key=$youtubeApiKey"
        val response: HttpResponse = client.get(apiUrl) { youtubeTimeouts() }
        val videoResponse: YouTubeVideoSnippetResponse = response.body()
        videoResponse.items?.firstOrNull()?.snippet?.title
    }

    /**
     * Timeouts for the YouTube Data API calls that an interaction waits on.
     *
     * `requestTimeoutMillis` is the one that matters — it bounds the whole
     * call, including a server that accepts the connection and then stops
     * talking, which is exactly the hang a connect timeout alone would miss.
     */
    private fun HttpRequestBuilder.youtubeTimeouts() = timeout {
        requestTimeoutMillis = youtubeRequestTimeoutMs
        connectTimeoutMillis = youtubeConnectTimeoutMs
        socketTimeoutMillis = youtubeRequestTimeoutMs
    }

    // Function to parse ISO 8601 duration and return a Kotlin `Duration`
    fun parseIso8601Duration(duration: String): Duration? {
        // Regex to match ISO 8601 duration
        val regex = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
        val matchResult = regex.matchEntire(duration) ?: return null

        // Safely extract hours, minutes, and seconds from the regex match result
        val hours = matchResult.groups[1]?.value?.toLongOrNull() ?: 0
        val minutes = matchResult.groups[2]?.value?.toLongOrNull() ?: 0
        val seconds = matchResult.groups[3]?.value?.toLongOrNull() ?: 0

        // Calculate total seconds
        val totalSeconds = (hours * 3600) + (minutes * 60) + seconds

        // Return as Duration
        return totalSeconds.seconds
    }

    // Helper function to extract video ID from YouTube URL
    private fun extractVideoIdFromUrl(url: String): String? {
        // Exclude `/` from the capture so a trailing slash (e.g. `…/shorts/ID/`)
        // doesn't leak into the id.
        val regex = Regex("(?<=v=|/videos/|embed/|youtu\\.be/|/v/|/e/|watch\\?v=|&v=|^youtu\\.be/|/shorts/)([^#&?/\\n]+)")
        return regex.find(url)?.value
    }

    @Serializable
    data class YouTubeVideoResponse(val items: List<VideoItem>?)

    @Serializable
    data class VideoItem(val contentDetails: ContentDetails?)

    @Serializable
    data class ContentDetails(val duration: String?)

    @Serializable
    data class YouTubeVideoSnippetResponse(val items: List<SnippetItem>?)

    @Serializable
    data class SnippetItem(val snippet: Snippet?)

    @Serializable
    data class Snippet(val title: String?)

    companion object {
        /**
         * Whole-call budget for a YouTube Data API lookup. Comfortably longer
         * than a healthy response and comfortably shorter than Discord's
         * 15-minute interaction window, so a slow API costs the user a clear
         * "we couldn't check the length" rather than a dead form.
         */
        const val YOUTUBE_REQUEST_TIMEOUT_MS = 10_000L
        const val YOUTUBE_CONNECT_TIMEOUT_MS = 5_000L
    }
}
