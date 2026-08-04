package bot.toby.helpers

import bot.toby.command.commands.fetch.TestHttpHelperHelper.ACTION_SURGE_INITIAL_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.ACTION_SURGE_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.BLIND_QUERY_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.BLIND_QUERY_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.COVER_INITIAL_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.COVER_INITIAL_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.DND_URL_START
import bot.toby.command.commands.fetch.TestHttpHelperHelper.EMPTY_QUERY_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.GRAPPLED_INITIAL_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.GRAPPLED_INITIAL_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.PERSONA_SNIPPET_API_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.PERSONA_SNIPPET_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.PERSONA_VIDEO_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.YOUTUBE_SNIPPET_EMPTY_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.YOUTUBE_SNIPPET_NULL_ITEMS_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.YOUTUBE_VIDEO_URL
import bot.toby.command.commands.fetch.TestHttpHelperHelper.createMockHttpClient
import bot.toby.command.commands.fetch.TestHttpHelperHelper.createYouTubeMockHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class HttpHelperTest {

    @Test
    fun `fetchFromGet returns expected JSON response for fireball`() = runBlocking {
        val httpClient = createMockHttpClient(
            initialUrlRequest = FIREBALL_INITIAL_URL,
            initialResponseJson = FIREBALL_INITIAL_RESPONSE,
        )

        val responseString = httpClient.fetchFromGet(FIREBALL_INITIAL_URL)
        assertEquals(FIREBALL_INITIAL_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for grappled`() = runBlocking {
        val httpClient = createMockHttpClient(
            initialUrlRequest = GRAPPLED_INITIAL_URL,
            initialResponseJson = GRAPPLED_INITIAL_RESPONSE
        )

        val responseString = httpClient.fetchFromGet(GRAPPLED_INITIAL_URL)
        assertEquals(GRAPPLED_INITIAL_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for cover`() = runBlocking {
        val httpClient = createMockHttpClient(
            initialUrlRequest = COVER_INITIAL_URL,
            initialResponseJson = COVER_INITIAL_RESPONSE
        )

        val responseString = httpClient.fetchFromGet(COVER_INITIAL_URL)
        assertEquals(COVER_INITIAL_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for action surge`() = runBlocking {
        val httpClient = createMockHttpClient(
            initialUrlRequest = ACTION_SURGE_INITIAL_URL,
            initialResponseJson = ACTION_SURGE_RESPONSE
        )

        val responseString = httpClient.fetchFromGet(ACTION_SURGE_INITIAL_URL)
        assertEquals(ACTION_SURGE_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for blind query`() = runBlocking {
        val httpClient = createMockHttpClient(
            queryRematchUrlRequest = BLIND_QUERY_URL,
            queryRematchResponse = BLIND_QUERY_RESPONSE
        )

        val responseString = httpClient.fetchFromGet(BLIND_QUERY_URL)
        assertEquals(BLIND_QUERY_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for empty query`() = runBlocking {
        val httpClient = createMockHttpClient(
            queryRematchUrlRequest = "$DND_URL_START/conditions?name=nonexistent",
            queryRematchResponse = EMPTY_QUERY_RESPONSE
        )

        val responseString = httpClient.fetchFromGet("$DND_URL_START/conditions?name=nonexistent")
        assertEquals(EMPTY_QUERY_RESPONSE, responseString)
    }

    @Test
    fun testParseHoursMinutesSeconds() {
        val duration = "PT1H30M15S"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(1.hours + 30.minutes + 15.seconds, result)
    }

    @Test
    fun testParseOnlyHours() {
        val duration = "PT2H"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(2.hours, result)
    }

    @Test
    fun testParseOnlyMinutes() {
        val duration = "PT45M"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(45.minutes, result)
    }

    @Test
    fun testParseOnlySeconds() {
        val duration = "PT15S"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(15.seconds, result)
    }

    @Test
    fun testParseMinutesAndSeconds() {
        val duration = "PT5M10S"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(5.minutes + 10.seconds, result)
    }

    @Test
    fun testParseHoursAndSeconds() {
        val duration = "PT1H45S"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(1.hours + 45.seconds, result)
    }

    @Test
    fun testParseHoursAndMinutesWithoutSeconds() {
        val duration = "PT1H30M"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(1.hours + 30.minutes, result)
    }

    @Test
    fun testParseEmptyString() {
        val duration = ""
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertNull(result)
    }

    @Test
    fun testParseInvalidString() {
        val duration = "InvalidFormat"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertNull(result)
    }

    @Test
    fun testParseJustPT() {
        val duration = "PT"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(0.seconds, result)
    }

    @Test
    fun testParseZeroDuration() {
        val duration = "PT0S"
        val httpClient = createMockHttpClient()
        val result = httpClient.parseIso8601Duration(duration)
        assertEquals(0.seconds, result)
    }

    @Test
    fun `getYouTubeVideoTitle returns title for valid YouTube URL`() = runBlocking {
        val httpClient = createYouTubeMockHttpClient()
        val result = httpClient.getYouTubeVideoTitle(YOUTUBE_VIDEO_URL)
        assertEquals("Never Gonna Give You Up", result)
    }

    @Test
    fun `getYouTubeVideoTitle returns null for non-YouTube URL`() = runBlocking {
        val httpClient = createYouTubeMockHttpClient()
        val result = httpClient.getYouTubeVideoTitle("https://example.com/audio.mp3")
        assertNull(result)
    }

    @Test
    fun `getYouTubeVideoTitle returns null when items list is empty`() = runBlocking {
        val httpClient = createYouTubeMockHttpClient(snippetResponse = YOUTUBE_SNIPPET_EMPTY_RESPONSE)
        val result = httpClient.getYouTubeVideoTitle(YOUTUBE_VIDEO_URL)
        assertNull(result)
    }

    @Test
    fun `getYouTubeVideoTitle returns null when items is null`() = runBlocking {
        val httpClient = createYouTubeMockHttpClient(snippetResponse = YOUTUBE_SNIPPET_NULL_ITEMS_RESPONSE)
        val result = httpClient.getYouTubeVideoTitle(YOUTUBE_VIDEO_URL)
        assertNull(result)
    }

    @Test
    fun `getYouTubeVideoTitle returns title for Persona vibes video URL`() = runBlocking {
        val httpClient = createYouTubeMockHttpClient(
            snippetUrl = PERSONA_SNIPPET_API_URL,
            snippetResponse = PERSONA_SNIPPET_RESPONSE
        )
        val result = httpClient.getYouTubeVideoTitle(PERSONA_VIDEO_URL)
        assertEquals("WOW. THIS IS GIVING ME MAJOR PERSONA VIBES.", result)
    }

    @Test
    fun `getYouTubeVideoTitle returns title for YouTube Shorts URL`() = runBlocking {
        val httpClient = createYouTubeMockHttpClient()
        val result = httpClient.getYouTubeVideoTitle("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        assertEquals("Never Gonna Give You Up", result)
    }

    // --- timeouts -----------------------------------------------------------
    //
    // Setting an intro blocks on these lookups. The shared client installs
    // HttpTimeout without configuring one, leaving each call site to opt in,
    // and these never did — so a googleapis request that *hung* rather than
    // failed left the user's deferred reply unanswered until Discord gave up,
    // with nothing saved and nothing said.

    /** A client whose engine never answers, standing in for a hung API. */
    private fun hangingClient(): HttpHelper {
        val engine = MockEngine {
            delay(Long.MAX_VALUE)
            respond("")
        }
        return HttpHelper(
            HttpClient(engine) {
                install(HttpTimeout)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
            youtubeApiKey = "test-key",
            // Real budget is seconds; the behaviour under test is "it gives up
            // at all", so don't spend them on every CI run.
            youtubeRequestTimeoutMs = 100L,
            youtubeConnectTimeoutMs = 100L,
        )
    }

    @Test
    fun `a hanging duration lookup gives up instead of waiting forever`() = runBlocking {
        assertThrows(HttpRequestTimeoutException::class.java) {
            runBlocking { hangingClient().getYouTubeVideoDuration(YOUTUBE_VIDEO_URL) }
        }
        Unit
    }

    @Test
    fun `a hanging title lookup gives up instead of waiting forever`() = runBlocking {
        assertThrows(HttpRequestTimeoutException::class.java) {
            runBlocking { hangingClient().getYouTubeVideoTitle(YOUTUBE_VIDEO_URL) }
        }
        Unit
    }

    @Test
    fun `the budget leaves room for a healthy response and stays well inside Discord's window`() {
        // Long enough not to trip on a slow-but-working API; short enough that
        // the user gets an answer rather than a dead interaction.
        assertTrue(HttpHelper.YOUTUBE_REQUEST_TIMEOUT_MS in 2_000..30_000)
        assertTrue(HttpHelper.YOUTUBE_CONNECT_TIMEOUT_MS <= HttpHelper.YOUTUBE_REQUEST_TIMEOUT_MS)
    }
}
