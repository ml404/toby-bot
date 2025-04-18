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
import bot.toby.command.commands.fetch.TestHttpHelperHelper.createMockHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
