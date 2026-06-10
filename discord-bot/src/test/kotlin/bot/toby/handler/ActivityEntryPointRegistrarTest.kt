package bot.toby.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.SelfUser
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

class ActivityEntryPointRegistrarTest {

    private fun jdaWith(token: String): JDA {
        val selfUser = mockk<SelfUser> {
            every { applicationId } returns "12345"
        }
        return mockk {
            every { this@mockk.selfUser } returns selfUser
            every { this@mockk.token } returns token
        }
    }

    private fun response(status: Int, body: String = ""): HttpResponse<String> =
        mockk<HttpResponse<String>>().also {
            every { it.statusCode() } returns status
            every { it.body() } returns body
        }

    @Test
    fun `posts the entry point command to the application commands endpoint`() {
        val http = mockk<HttpClient>()
        val requestSlot = slot<HttpRequest>()
        every { http.sendAsync(capture(requestSlot), any<HttpResponse.BodyHandler<String>>()) } returns
            CompletableFuture.completedFuture(response(201))
        val registrar = ActivityEntryPointRegistrar(http)

        val result = registrar.register(jdaWith("Bot abc123")).join()

        assertTrue(result)
        val request = requestSlot.captured
        assertEquals("POST", request.method())
        assertEquals(
            "${ActivityEntryPointRegistrar.API_BASE}/applications/12345/commands",
            request.uri().toString()
        )
        assertEquals("Bot abc123", request.headers().firstValue("Authorization").orElse(""))
    }

    @Test
    fun `a raw token without the Bot prefix is normalised`() {
        val http = mockk<HttpClient>()
        val requestSlot = slot<HttpRequest>()
        every { http.sendAsync(capture(requestSlot), any<HttpResponse.BodyHandler<String>>()) } returns
            CompletableFuture.completedFuture(response(200))
        val registrar = ActivityEntryPointRegistrar(http)

        registrar.register(jdaWith("abc123")).join()

        assertEquals("Bot abc123", requestSlot.captured.headers().firstValue("Authorization").orElse(""))
    }

    @Test
    fun `a rejected create (activities not enabled) resolves false without throwing`() {
        val http = mockk<HttpClient>()
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<String>>()) } returns
            CompletableFuture.completedFuture(response(400, """{"message":"Invalid command type"}"""))
        val registrar = ActivityEntryPointRegistrar(http)

        assertFalse(registrar.register(jdaWith("Bot abc123")).join())
    }

    @Test
    fun `a network failure resolves false without throwing`() {
        val http = mockk<HttpClient>()
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<String>>()) } returns
            CompletableFuture.failedFuture(RuntimeException("connection reset"))
        val registrar = ActivityEntryPointRegistrar(http)

        assertFalse(registrar.register(jdaWith("Bot abc123")).join())
    }
}
