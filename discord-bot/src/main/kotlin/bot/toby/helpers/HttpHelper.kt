package bot.toby.helpers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpHelper(private val client: HttpClient, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

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
}
