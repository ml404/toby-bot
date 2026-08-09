package web.service

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One HTTP round trip's outcome, reduced to what the Magic lookups need. */
data class HttpReply(val statusCode: Int, val body: String)

/**
 * The seam between [CubeWebService] and the network.
 *
 * The service used to build its own [HttpClient] inline, which meant every
 * status-code branch, retry, cache hit and rate-limit path could only be
 * exercised by actually calling Scryfall — so none of them were tested. This
 * interface is that layer, small enough that a test can hand over canned
 * replies and assert on the requests that were made.
 */
fun interface HttpTransport {
    /**
     * Sends [request] and returns its status and body.
     *
     * @throws IOException when the call can't be completed.
     */
    fun send(request: HttpRequest): HttpReply
}

/** The real transport: a shared [HttpClient] with the project's timeouts. */
class JdkHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : HttpTransport {

    override fun send(request: HttpRequest): HttpReply {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpReply(response.statusCode(), response.body())
    }
}

/** A GET with the headers and timeout every Magic lookup uses. */
internal fun getRequest(url: String): HttpRequest =
    HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build()

/** A JSON POST with the same headers and timeout. */
internal fun postRequest(url: String, body: String): HttpRequest =
    HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

private const val USER_AGENT = "tobybot-web/1.0"
