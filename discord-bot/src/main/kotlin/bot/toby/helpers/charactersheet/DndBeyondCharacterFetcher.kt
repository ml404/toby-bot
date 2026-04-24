package bot.toby.helpers.charactersheet

import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class DndBeyondCharacterFetcher(
    private val client: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val logger = LoggerFactory.getLogger(DndBeyondCharacterFetcher::class.java)
    private val gson = Gson()

    suspend fun fetch(characterId: Long): FetchResult = withContext(dispatcher) {
        val url = "$baseUrl/character/v5/character/$characterId?includeCustomItems=true"
        val response = try {
            client.get(url) {
                header(HttpHeaders.Accept, "application/json")
            }
        } catch (e: Exception) {
            logger.warn("D&D Beyond fetch failed for id={} due to transport error", characterId, e)
            return@withContext FetchResult.Unavailable(e)
        }

        classify(characterId, response)
    }

    private suspend fun classify(characterId: Long, response: HttpResponse): FetchResult {
        return when (response.status) {
            HttpStatusCode.OK -> unwrapAndDecode(characterId, response.bodyAsText())
            HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized -> FetchResult.Forbidden
            HttpStatusCode.NotFound -> FetchResult.NotFound
            else -> {
                logger.warn(
                    "D&D Beyond fetch returned unexpected status {} for id={}: {}",
                    response.status, characterId, response.bodyAsText().take(500)
                )
                FetchResult.Unavailable()
            }
        }
    }

    private fun unwrapAndDecode(characterId: Long, body: String): FetchResult {
        val envelope = try {
            gson.fromJson(body, Envelope::class.java)
        } catch (e: JsonSyntaxException) {
            logger.warn("D&D Beyond response for id={} was not valid JSON", characterId, e)
            return FetchResult.Unavailable(e)
        }

        if (envelope?.success == false) {
            logger.info("D&D Beyond reported success=false for id={}: {}", characterId, envelope.message)
            return FetchResult.NotFound
        }

        val data = envelope?.data
        if (data == null || !data.isJsonObject) {
            logger.warn("D&D Beyond response for id={} had no data object", characterId)
            return FetchResult.Unavailable()
        }

        val dataJson = data.toString()
        val sheet = try {
            CharacterSheetCodec.decode(dataJson)
        } catch (e: JsonSyntaxException) {
            logger.warn("Failed to decode D&D Beyond character sheet for id={}", characterId, e)
            return FetchResult.Unavailable(e)
        }

        return FetchResult.Success(sheet, dataJson)
    }

    private data class Envelope(
        val id: Long? = null,
        val success: Boolean? = null,
        val message: String? = null,
        val data: JsonElement? = null,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://character-service.dndbeyond.com"
    }
}
