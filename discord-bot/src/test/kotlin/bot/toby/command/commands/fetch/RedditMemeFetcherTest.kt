package bot.toby.command.commands.fetch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class RedditMemeFetcherTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fetcherWith(engine: MockEngine): RedditMemeFetcher =
        RedditMemeFetcher(HttpClient(engine) { install(HttpTimeout) }, Dispatchers.Unconfined)

    private fun listing(vararg posts: String): String =
        """{"data":{"children":[${posts.joinToString(",")}]}}"""

    private fun post(
        title: String = "A meme",
        url: String = "https://i.redd.it/x.jpg",
        author: String = "someone",
        nsfw: Boolean = false,
        video: Boolean = false,
    ): String =
        """{"data":{"title":"$title","url":"$url","author":"$author","over_18":$nsfw,"is_video":$video}}"""

    @Test
    fun `fetch returns a parsed post on success`() = runBlocking {
        val fetcher = fetcherWith(
            MockEngine { respond(listing(post(title = "Spidey", url = "https://i.redd.it/s.jpg", author = "raimi")), HttpStatusCode.OK, jsonHeaders) }
        )
        val result = fetcher.fetch("raimimemes", "day", 1)
        val success = assertInstanceOf(RedditMemeFetcher.Result.Success::class.java, result)
        assertEquals("Spidey", success.title)
        assertEquals("https://i.redd.it/s.jpg", success.url)
        assertEquals("raimi", success.author)
    }

    @Test
    fun `fetch hits the subreddit's top listing URL`() = runBlocking {
        val engine = MockEngine { respond(listing(post()), HttpStatusCode.OK, jsonHeaders) }
        fetcherWith(engine).fetch("memes", "week", 5)
        val url = engine.requestHistory.single().url.toString()
        assertTrue(url.contains("/r/memes/"), "url was: $url")
        assertTrue(url.contains("limit=5"), "url was: $url")
        assertTrue(url.contains("t=week"), "url was: $url")
    }

    @Test
    fun `fetch maps a non-200 to a friendly error`() = runBlocking {
        val result = fetcherWith(MockEngine { respondError(HttpStatusCode.InternalServerError) }).fetch("memes", "day", 5)
        val error = assertInstanceOf(RedditMemeFetcher.Result.Error::class.java, result)
        assertTrue(error.message.contains("Failed to fetch meme"))
    }

    @Test
    fun `fetch reports an empty listing`() = runBlocking {
        val result = fetcherWith(MockEngine { respond(listing(), HttpStatusCode.OK, jsonHeaders) }).fetch("emptysub", "day", 5)
        val error = assertInstanceOf(RedditMemeFetcher.Result.Error::class.java, result)
        assertTrue(error.message.contains("No memes found"))
    }

    @Test
    fun `fetch skips an NSFW post`() = runBlocking {
        val result = fetcherWith(MockEngine { respond(listing(post(nsfw = true)), HttpStatusCode.OK, jsonHeaders) }).fetch("memes", "day", 1)
        val error = assertInstanceOf(RedditMemeFetcher.Result.Error::class.java, result)
        assertTrue(error.message.contains("NSFW"))
    }

    @Test
    fun `fetch skips a video post`() = runBlocking {
        val result = fetcherWith(MockEngine { respond(listing(post(video = true)), HttpStatusCode.OK, jsonHeaders) }).fetch("memes", "day", 1)
        val error = assertInstanceOf(RedditMemeFetcher.Result.Error::class.java, result)
        assertTrue(error.message.contains("video"))
    }

    @Test
    fun `fetch surfaces a transport failure as an error`() = runBlocking {
        val result = fetcherWith(MockEngine { throw IOException("reddit down") }).fetch("memes", "day", 1)
        val error = assertInstanceOf(RedditMemeFetcher.Result.Error::class.java, result)
        assertTrue(error.message.contains("Failed to fetch meme"))
    }
}
