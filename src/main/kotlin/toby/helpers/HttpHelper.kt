package toby.helpers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils
import java.io.IOException
import java.text.ParseException

class HttpHelper {
    suspend fun fetchFromGet(url: String?, dispatcher: CoroutineDispatcher = Dispatchers.IO): String = withContext(dispatcher) {
        if (url.isNullOrBlank()) return@withContext ""
        try {
            HttpClients.createDefault().use { httpClient: CloseableHttpClient ->
                val httpGet = HttpGet(url).apply {
                    addHeader("Accept", "application/json")
                }
                httpClient.execute(httpGet).use { response: CloseableHttpResponse ->
                    if (response.code == 200) {
                        return@use EntityUtils.toString(response.entity)
                    } else {
                        return@use ""
                    }
                }
            }
        } catch (e: IOException) {
            throw RuntimeException("I/O error occurred", e)
        } catch (e: ParseException) {
            throw RuntimeException("Parsing error occurred", e)
        }
    }
}
