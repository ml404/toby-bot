package bot.toby.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.SelfUser
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.utils.data.DataArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

/**
 * The Entry Point command is the whole reason this class exists: Discord
 * answers a bulk overwrite that would drop it with
 * `50240: You cannot remove this app's Entry Point command in a bulk update
 * operation`, which fails the *entire* request — so every command silently
 * stops updating, not just the activity one.
 */
class GlobalCommandRegistrarTest {

    private lateinit var http: HttpClient
    private lateinit var jda: JDA
    private lateinit var registrar: GlobalCommandRegistrar

    private val commands: List<CommandData> = listOf(
        Commands.slash("listintros", "Show your intros"),
        Commands.message("Set as my intro"),
    )

    private val entryPointJson =
        """{"id":"9","application_id":"1","name":"launch","type":4,"handler":2,"description":""}"""

    @BeforeEach
    fun setUp() {
        http = mockk()
        val selfUser = mockk<SelfUser> { every { applicationId } returns "1" }
        jda = mockk {
            every { this@mockk.selfUser } returns selfUser
            every { token } returns "Bot secret-token"
        }
        registrar = GlobalCommandRegistrar(http)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun response(status: Int, body: String): HttpResponse<String> = mockk {
        every { statusCode() } returns status
        every { this@mockk.body() } returns body
    }

    /** Stubs the GET then the PUT, and captures every request sent. */
    private fun stubExchange(
        getStatus: Int = 200,
        getBody: String = "[]",
        putStatus: Int = 200,
    ): MutableList<HttpRequest> {
        val requests = mutableListOf<HttpRequest>()
        val slot = slot<HttpRequest>()
        every { http.sendAsync(capture(slot), any<HttpResponse.BodyHandler<String>>()) } answers {
            val request = slot.captured
            requests += request
            val r = if (request.method() == "GET") response(getStatus, getBody) else response(putStatus, "{}")
            CompletableFuture.completedFuture(r)
        }
        return requests
    }

    private fun bodyOf(request: HttpRequest): String {
        // BodyPublishers.ofString exposes its content only through the
        // Flow API; subscribe synchronously and reassemble.
        val publisher = request.bodyPublisher().orElseThrow()
        val chunks = StringBuilder()
        val done = CompletableFuture<String>()
        publisher.subscribe(object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) = s.request(Long.MAX_VALUE)
            override fun onNext(item: java.nio.ByteBuffer) {
                chunks.append(Charsets.UTF_8.decode(item))
            }
            override fun onError(t: Throwable) = done.completeExceptionally(t).let { }
            override fun onComplete() = done.complete(chunks.toString()).let { }
        })
        return done.get()
    }

    private fun putBody(requests: List<HttpRequest>): DataArray =
        DataArray.fromJson(bodyOf(requests.single { it.method() == "PUT" }))

    @Test
    fun `an existing entry point command is carried through the overwrite`() {
        val requests = stubExchange(getBody = "[$entryPointJson]")

        assertTrue(registrar.register(jda, commands).get())

        val sent = putBody(requests)
        assertEquals(3, sent.length())
        val names = (0 until sent.length()).map { sent.getObject(it).getString("name") }
        assertTrue(names.contains("launch"), "entry point must survive: $names")
        assertTrue(names.contains("listintros"))
        assertTrue(names.contains("Set as my intro"))
    }

    @Test
    fun `the preserved entry point keeps its type so Discord accepts it`() {
        val requests = stubExchange(getBody = "[$entryPointJson]")

        registrar.register(jda, commands).get()

        val sent = putBody(requests)
        val launch = (0 until sent.length()).map { sent.getObject(it) }.single { it.getString("name") == "launch" }
        assertEquals(GlobalCommandRegistrar.PRIMARY_ENTRY_POINT_TYPE, launch.getInt("type"))
        assertEquals(2, launch.getInt("handler"))
    }

    @Test
    fun `an app without activities sends only its own commands`() {
        val requests = stubExchange(getBody = "[]")

        assertTrue(registrar.register(jda, commands).get())

        assertEquals(2, putBody(requests).length())
    }

    @Test
    fun `ordinary commands in the existing set are replaced, not duplicated`() {
        // A stale command that no longer exists in code must not be preserved
        // — only the Entry Point gets that treatment.
        val stale = """{"id":"8","name":"oldcommand","type":1,"description":"gone"}"""
        val requests = stubExchange(getBody = "[$stale,$entryPointJson]")

        registrar.register(jda, commands).get()

        val sent = putBody(requests)
        val names = (0 until sent.length()).map { sent.getObject(it).getString("name") }
        assertFalse(names.contains("oldcommand"), "stale commands should be dropped: $names")
        assertEquals(3, sent.length())
    }

    @Test
    fun `the overwrite still goes out when the existing set cannot be read`() {
        val requests = stubExchange(getStatus = 401, getBody = "unauthorized")

        assertTrue(registrar.register(jda, commands).get())

        assertEquals(2, putBody(requests).length())
    }

    @Test
    fun `unparseable existing commands do not stop the overwrite`() {
        val requests = stubExchange(getBody = "not json")

        assertTrue(registrar.register(jda, commands).get())

        assertEquals(2, putBody(requests).length())
    }

    @Test
    fun `a rejected overwrite reports failure`() {
        stubExchange(getBody = "[$entryPointJson]", putStatus = 400)

        assertFalse(registrar.register(jda, commands).get())
    }

    @Test
    fun `a transport failure reports failure rather than throwing`() {
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<String>>()) } answers {
            CompletableFuture.failedFuture(java.io.IOException("connection reset"))
        }

        assertFalse(registrar.register(jda, commands).get())
    }

    @Test
    fun `the bot token is normalised whether or not JDA includes the prefix`() {
        every { jda.token } returns "secret-token"
        val requests = stubExchange(getBody = "[]")

        registrar.register(jda, commands).get()

        requests.forEach {
            assertEquals("Bot secret-token", it.headers().firstValue("Authorization").orElseThrow())
        }
    }

    @Test
    fun `the overwrite targets the application's global command endpoint`() {
        val requests = stubExchange(getBody = "[]")

        registrar.register(jda, commands).get()

        val put = requests.single { it.method() == "PUT" }
        assertEquals("${GlobalCommandRegistrar.API_BASE}/applications/1/commands", put.uri().toString())
    }
}
