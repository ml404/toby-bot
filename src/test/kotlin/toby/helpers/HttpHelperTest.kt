package toby.helpers

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_URL

internal class HttpHelperTest {

    @Test
    fun `fetchFromGet returns expected JSON response`() = runBlocking {
        // Setup MockEngine
        val mockEngine = MockEngine { request ->
            // Check the request URL and provide a response
            when (request.url.toString()) {
                FIREBALL_INITIAL_URL -> {
                    respond(
                        content = FIREBALL_INITIAL_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                else -> respond(
                    content = "Not found",
                    status = HttpStatusCode.NotFound
                )
            }
        }

        // Create HttpClient with MockEngine
        val client = HttpClient(mockEngine)

        // Create an instance of HttpHelper
        val httpHelper = HttpHelper(client)

        // Test fetchFromGet
        val responseString = httpHelper.fetchFromGet("https://www.dnd5eapi.co/api/spells/fireball")
        assertEquals(FIREBALL_INITIAL_RESPONSE, responseString)
    }

}
