package bot.toby.helpers.charactersheet

import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DndBeyondCharacterFetcherTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun fetcher(engine: MockEngine) = DndBeyondCharacterFetcher(
        client = HttpClient(engine),
        dispatcher = dispatcher,
    )

    @Test
    fun `200 with envelope success returns Success with parsed sheet and raw data json`() = runTest {
        val body = """
            {
              "id": 48690485,
              "success": true,
              "message": "",
              "data": {
                "id": 48690485,
                "name": "Eeyore",
                "stats": [
                  { "id": 1, "value": 10 },
                  { "id": 2, "value": 14 },
                  { "id": 3, "value": 17 },
                  { "id": 4, "value": 15 },
                  { "id": 5, "value": 18 },
                  { "id": 6, "value": 10 }
                ],
                "baseHitPoints": 68
              }
            }
        """.trimIndent()
        val engine = MockEngine { request ->
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }

        val result = fetcher(engine).fetch(48690485L)

        assertTrue(result is FetchResult.Success, "expected Success, was $result")
        result as FetchResult.Success
        assertEquals("Eeyore", result.sheet.name)
        assertEquals(48690485L, result.sheet.id)
        assertEquals(2, result.sheet.modifier(2)) // DEX 14 -> +2
        assertTrue(result.rawJson.contains("\"name\":\"Eeyore\""))
        assertTrue(!result.rawJson.contains("\"success\""), "rawJson must be the unwrapped data object")

        val request = engine.requestHistory.single()
        assertEquals(
            "https://character-service.dndbeyond.com/character/v5/character/48690485?includeCustomItems=true",
            request.url.toString()
        )
    }

    @Test
    fun `403 returns Forbidden`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Forbidden) }
        val result = fetcher(engine).fetch(1L)
        assertEquals(FetchResult.Forbidden, result)
    }

    @Test
    fun `401 returns Forbidden`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val result = fetcher(engine).fetch(1L)
        assertEquals(FetchResult.Forbidden, result)
    }

    @Test
    fun `404 returns NotFound`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        val result = fetcher(engine).fetch(1L)
        assertEquals(FetchResult.NotFound, result)
    }

    @Test
    fun `500 returns Unavailable`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val result = fetcher(engine).fetch(1L)
        assertTrue(result is FetchResult.Unavailable)
    }

    @Test
    fun `network exception returns Unavailable with cause`() = runTest {
        val boom = RuntimeException("connection refused")
        val engine = MockEngine { throw boom }
        val result = fetcher(engine).fetch(1L)
        assertTrue(result is FetchResult.Unavailable)
        result as FetchResult.Unavailable
        assertNotNull(result.cause)
    }

    @Test
    fun `envelope with success false returns NotFound`() = runTest {
        val body = """{"success": false, "message": "gone"}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = fetcher(engine).fetch(1L)
        assertEquals(FetchResult.NotFound, result)
    }

    @Test
    fun `malformed json returns Unavailable`() = runTest {
        val engine = MockEngine {
            respond("not-json", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = fetcher(engine).fetch(1L)
        assertTrue(result is FetchResult.Unavailable)
    }
}
