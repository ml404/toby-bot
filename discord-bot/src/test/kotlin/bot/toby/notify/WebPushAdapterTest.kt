package bot.toby.notify

import com.fasterxml.jackson.databind.ObjectMapper
import common.notification.PushPayload
import database.dto.PushSubscriptionDto
import database.service.user.PushSubscriptionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Coverage for [WebPushAdapter] without spinning up a real push service.
 * A fake [PushTransport] captures every (endpoint, body) pair and
 * returns a caller-controlled HTTP status so we can pin the contract
 * for 2xx (mark-used), 410 (prune), and 5xx (skip silently) responses.
 */
class WebPushAdapterTest {

    private val discordId = 123L
    private val mapper = ObjectMapper()

    private lateinit var subscriptions: PushSubscriptionService
    private lateinit var transport: RecordingTransport
    private lateinit var adapter: WebPushAdapter

    @BeforeEach
    fun setup() {
        subscriptions = mockk(relaxed = true)
        transport = RecordingTransport()
        adapter = WebPushAdapter(subscriptions, mapper, transport)
    }

    @Test
    fun `deliver with no subscriptions is a silent no-op`() {
        every { subscriptions.listForUser(discordId) } returns emptyList()
        adapter.deliver(discordId, PushPayload("t", "b"))
        assertTrue(transport.sends.isEmpty(), "no subscriptions → no transport calls")
    }

    @Test
    fun `deliver fans out to every subscription with the JSON envelope`() {
        every { subscriptions.listForUser(discordId) } returns listOf(
            sub("https://fcm.googleapis.com/laptop"),
            sub("https://fcm.googleapis.com/phone"),
        )
        transport.statusFor = { 201 }

        adapter.deliver(discordId, PushPayload("Hello", "World", deepLink = "/preferences"))

        assertEquals(2, transport.sends.size)
        val endpoints = transport.sends.map { it.endpoint }.toSet()
        assertEquals(setOf("https://fcm.googleapis.com/laptop", "https://fcm.googleapis.com/phone"), endpoints)
        transport.sends.forEach { call ->
            val json = String(call.body)
            assertTrue(json.contains("\"title\":\"Hello\""), "envelope title: $json")
            assertTrue(json.contains("\"body\":\"World\""), "envelope body: $json")
            assertTrue(json.contains("\"deepLink\":\"/preferences\""), "envelope deeplink: $json")
        }
    }

    @Test
    fun `2xx response marks the subscription used`() {
        every { subscriptions.listForUser(discordId) } returns listOf(sub("https://ok"))
        transport.statusFor = { 200 }

        adapter.deliver(discordId, PushPayload("t", "b"))

        verify(exactly = 1) { subscriptions.markUsed("https://ok", any()) }
        verify(exactly = 0) { subscriptions.unsubscribe(any()) }
    }

    @Test
    fun `410 Gone prunes the subscription`() {
        every { subscriptions.listForUser(discordId) } returns listOf(sub("https://gone"))
        transport.statusFor = { 410 }

        adapter.deliver(discordId, PushPayload("t", "b"))

        verify(exactly = 1) { subscriptions.unsubscribe("https://gone") }
        verify(exactly = 0) { subscriptions.markUsed(any(), any()) }
    }

    @Test
    fun `404 also prunes the subscription`() {
        every { subscriptions.listForUser(discordId) } returns listOf(sub("https://missing"))
        transport.statusFor = { 404 }

        adapter.deliver(discordId, PushPayload("t", "b"))

        verify(exactly = 1) { subscriptions.unsubscribe("https://missing") }
    }

    @Test
    fun `5xx response neither marks nor prunes — will retry later`() {
        every { subscriptions.listForUser(discordId) } returns listOf(sub("https://flake"))
        transport.statusFor = { 503 }

        adapter.deliver(discordId, PushPayload("t", "b"))

        verify(exactly = 0) { subscriptions.unsubscribe(any()) }
        verify(exactly = 0) { subscriptions.markUsed(any(), any()) }
    }

    @Test
    fun `transport exception on one endpoint does not abort fan-out to others`() {
        every { subscriptions.listForUser(discordId) } returns listOf(
            sub("https://will-throw"),
            sub("https://will-succeed"),
        )
        // Throw on the first, succeed on the second.
        transport.statusFor = { endpoint ->
            if (endpoint == "https://will-throw") throw RuntimeException("simulated")
            else 200
        }

        adapter.deliver(discordId, PushPayload("t", "b"))

        // The second endpoint still got marked used; the first didn't.
        verify(exactly = 1) { subscriptions.markUsed("https://will-succeed", any()) }
        verify(exactly = 0) { subscriptions.markUsed("https://will-throw", any()) }
        verify(exactly = 0) { subscriptions.unsubscribe(any()) }
    }

    @Test
    fun `subscriptions lookup failure short-circuits without throwing`() {
        every { subscriptions.listForUser(discordId) } throws RuntimeException("db down")
        // No throw, no transport calls.
        adapter.deliver(discordId, PushPayload("t", "b"))
        assertTrue(transport.sends.isEmpty())
    }

    private fun sub(endpoint: String) = PushSubscriptionDto(
        endpoint = endpoint,
        discordId = discordId,
        p256dh = "pk-bytes",
        auth = "auth-bytes",
        userAgent = "Firefox",
        createdAt = Instant.now(),
        lastUsedAt = null,
    )

    /** Records every call; status code is computed per-endpoint via a lambda. */
    private class RecordingTransport : PushTransport {
        data class Call(val endpoint: String, val p256dh: String, val auth: String, val body: ByteArray)

        val sends = mutableListOf<Call>()
        var statusFor: (String) -> Int = { 200 }

        override fun send(endpoint: String, p256dh: String, auth: String, body: ByteArray): Int {
            sends.add(Call(endpoint, p256dh, auth, body))
            return statusFor(endpoint)
        }
    }
}
