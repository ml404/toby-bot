package toby.helpers

import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ParseException
import org.apache.hc.core5.http.io.entity.EntityUtils
import java.io.IOException

class HttpHelper {
    fun fetchFromGet(url: String?): String {
        try {
            HttpClients.createDefault().use { httpClient ->
                val httpGet = HttpGet(url)
                httpGet.addHeader("Accept", "application/json")
                val response = httpClient.execute(httpGet)
                if (response.code == 200) {
                    return EntityUtils.toString(response.entity)
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: ParseException) {
            throw RuntimeException(e)
        }
        return ""
    }
}
