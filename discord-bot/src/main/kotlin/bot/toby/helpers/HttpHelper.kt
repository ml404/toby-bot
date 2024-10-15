package bot.toby.helpers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HttpHelper(private val client: HttpClient, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val YOUTUBE_API_KEY = System.getenv("YOUTUBE_API_KEY")

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

    suspend fun getYouTubeVideoDuration(youtubeUrl: String): Duration? = withContext(dispatcher) {
        val videoId = extractVideoIdFromUrl(youtubeUrl) ?: return@withContext null
        val apiUrl = "https://www.googleapis.com/youtube/v3/videos?id=$videoId&part=contentDetails&key=$YOUTUBE_API_KEY"
        val response: HttpResponse = client.get(apiUrl)
        val videoResponse: YouTubeVideoResponse = response.body()
        client.close()

        val durationIso = videoResponse.items?.firstOrNull()?.contentDetails?.duration ?: return@withContext null
        return@withContext parseIso8601Duration(durationIso) // Return Duration
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
        val regex = Regex("(?<=v=|/videos/|embed/|youtu.be/|/v/|/e/|watch\\?v=|&v=|^youtu\\.be/)([^#&?\\n]+)")
        return regex.find(url)?.value
    }

    @Serializable
    data class YouTubeVideoResponse(val items: List<VideoItem>?)

    @Serializable
    data class VideoItem(val contentDetails: ContentDetails?)

    @Serializable
    data class ContentDetails(val duration: String?)

}
