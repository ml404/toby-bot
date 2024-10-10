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
import org.junit.jupiter.api.Test

internal class HttpHelperTest {

    @Test
    fun `fetchFromGet returns expected JSON response for fireball`() = runBlocking {
        val httpHelper = createMockHttpClient(
            initialUrlRequest = FIREBALL_INITIAL_URL,
            initialResponseJson = FIREBALL_INITIAL_RESPONSE,
        )

        val responseString = httpHelper.fetchFromGet(FIREBALL_INITIAL_URL)
        assertEquals(FIREBALL_INITIAL_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for grappled`() = runBlocking {
        val httpHelper = createMockHttpClient(
            initialUrlRequest = GRAPPLED_INITIAL_URL,
            initialResponseJson = GRAPPLED_INITIAL_RESPONSE
        )

        val responseString = httpHelper.fetchFromGet(GRAPPLED_INITIAL_URL)
        assertEquals(GRAPPLED_INITIAL_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for cover`() = runBlocking {
        val httpHelper = createMockHttpClient(
            initialUrlRequest = COVER_INITIAL_URL,
            initialResponseJson = COVER_INITIAL_RESPONSE
        )

        val responseString = httpHelper.fetchFromGet(COVER_INITIAL_URL)
        assertEquals(COVER_INITIAL_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for action surge`() = runBlocking {
        val httpHelper = createMockHttpClient(
            initialUrlRequest = ACTION_SURGE_INITIAL_URL,
            initialResponseJson = ACTION_SURGE_RESPONSE
        )

        val responseString = httpHelper.fetchFromGet(ACTION_SURGE_INITIAL_URL)
        assertEquals(ACTION_SURGE_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for blind query`() = runBlocking {
        val httpHelper = createMockHttpClient(
            queryRematchUrlRequest = BLIND_QUERY_URL,
            queryRematchResponse = BLIND_QUERY_RESPONSE
        )

        val responseString = httpHelper.fetchFromGet(BLIND_QUERY_URL)
        assertEquals(BLIND_QUERY_RESPONSE, responseString)
    }

    @Test
    fun `fetchFromGet returns expected JSON response for empty query`() = runBlocking {
        val httpHelper = createMockHttpClient(
            queryRematchUrlRequest = "$DND_URL_START/conditions?name=nonexistent",
            queryRematchResponse = EMPTY_QUERY_RESPONSE
        )

        val responseString = httpHelper.fetchFromGet("$DND_URL_START/conditions?name=nonexistent")
        assertEquals(EMPTY_QUERY_RESPONSE, responseString)
    }
}
