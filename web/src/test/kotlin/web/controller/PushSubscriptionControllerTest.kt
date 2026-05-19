package web.controller

import database.dto.PushSubscriptionDto
import database.service.PushSubscriptionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import java.time.Instant

class PushSubscriptionControllerTest {

    private val discordId = 100L
    private val otherUserId = 200L
    private val endpoint = "https://fcm.googleapis.com/fcm/send/abc"

    private lateinit var subscriptions: PushSubscriptionService
    private lateinit var user: OAuth2User
    private lateinit var request: HttpServletRequest
    private lateinit var controller: PushSubscriptionController

    @BeforeEach
    fun setup() {
        subscriptions = mockk(relaxed = true)
        user = mockk { every { getAttribute<String>("id") } returns discordId.toString() }
        request = mockk(relaxed = true) {
            every { getHeader("User-Agent") } returns "Mozilla/5.0 (X11; Linux) Firefox/119.0"
        }
        controller = PushSubscriptionController(
            subscriptions = subscriptions,
            vapidPublicKey = "test-public-key",
        )
    }

    // ---------- vapid-public-key ----------

    @Test
    fun `vapidPublicKey returns 200 with the configured key when set`() {
        val resp = controller.vapidPublicKey()
        assertEquals(200, resp.statusCode.value())
        assertEquals("test-public-key", resp.body!!.publicKey)
    }

    @Test
    fun `vapidPublicKey returns 404 when no key is configured`() {
        val empty = PushSubscriptionController(subscriptions, vapidPublicKey = "")
        assertEquals(404, empty.vapidPublicKey().statusCode.value())
    }

    // ---------- subscribe ----------

    @Test
    fun `subscribe rejects unauthenticated with 401`() {
        val anon = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }
        val resp = controller.subscribe(
            PushSubscriptionController.SubscribeRequest(endpoint, "pk", "auth"),
            request, anon,
        )
        assertEquals(401, resp.statusCode.value())
        verify(exactly = 0) { subscriptions.subscribe(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `subscribe rejects empty endpoint with 400`() {
        val resp = controller.subscribe(
            PushSubscriptionController.SubscribeRequest("", "pk", "auth"),
            request, user,
        )
        assertEquals(400, resp.statusCode.value())
    }

    @Test
    fun `subscribe rejects empty p256dh with 400`() {
        val resp = controller.subscribe(
            PushSubscriptionController.SubscribeRequest(endpoint, "", "auth"),
            request, user,
        )
        assertEquals(400, resp.statusCode.value())
    }

    @Test
    fun `subscribe rejects empty auth with 400`() {
        val resp = controller.subscribe(
            PushSubscriptionController.SubscribeRequest(endpoint, "pk", ""),
            request, user,
        )
        assertEquals(400, resp.statusCode.value())
    }

    @Test
    fun `subscribe persists and returns the row`() {
        val now = Instant.parse("2026-05-19T10:00:00Z")
        every {
            subscriptions.subscribe(discordId, endpoint, "pk", "auth", any(), any())
        } returns PushSubscriptionDto(
            endpoint = endpoint,
            discordId = discordId,
            p256dh = "pk",
            auth = "auth",
            userAgent = "Mozilla/5.0 (X11; Linux) Firefox/119.0",
            createdAt = now,
            lastUsedAt = null,
        )

        val resp = controller.subscribe(
            PushSubscriptionController.SubscribeRequest(endpoint, "pk", "auth"),
            request, user,
        )

        assertEquals(200, resp.statusCode.value())
        val body = resp.body!!
        assertEquals(endpoint, body.endpoint)
        assertEquals("Mozilla/5.0 (X11; Linux) Firefox/119.0", body.userAgent)
        assertEquals(now.toString(), body.createdAt)
    }

    @Test
    fun `subscribe truncates very long user agent to 512 chars`() {
        val long = "x".repeat(2000)
        every { request.getHeader("User-Agent") } returns long
        every {
            subscriptions.subscribe(any(), any(), any(), any(), any(), any())
        } returns PushSubscriptionDto(
            endpoint = endpoint, discordId = discordId, p256dh = "pk", auth = "auth",
            userAgent = "x".repeat(512), createdAt = Instant.now(), lastUsedAt = null,
        )

        controller.subscribe(
            PushSubscriptionController.SubscribeRequest(endpoint, "pk", "auth"),
            request, user,
        )

        verify {
            subscriptions.subscribe(
                discordId, endpoint, "pk", "auth",
                matchNullable { it != null && it.length <= 512 },
                any()
            )
        }
    }

    // ---------- unsubscribe ----------

    @Test
    fun `unsubscribe rejects unauthenticated with 401`() {
        val anon = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }
        val resp = controller.unsubscribe(
            PushSubscriptionController.UnsubscribeRequest(endpoint), anon
        )
        assertEquals(401, resp.statusCode.value())
        verify(exactly = 0) { subscriptions.unsubscribe(any()) }
    }

    @Test
    fun `unsubscribe rejects empty endpoint with 400`() {
        val resp = controller.unsubscribe(
            PushSubscriptionController.UnsubscribeRequest(""), user
        )
        assertEquals(400, resp.statusCode.value())
    }

    @Test
    fun `unsubscribe forbids removing someone else's row with 403`() {
        every { subscriptions.get(endpoint) } returns PushSubscriptionDto(
            endpoint = endpoint, discordId = otherUserId,
            p256dh = "pk", auth = "auth",
            userAgent = null, createdAt = Instant.now(), lastUsedAt = null,
        )

        val resp = controller.unsubscribe(
            PushSubscriptionController.UnsubscribeRequest(endpoint), user
        )
        assertEquals(403, resp.statusCode.value())
        verify(exactly = 0) { subscriptions.unsubscribe(any()) }
    }

    @Test
    fun `unsubscribe removes own subscription and returns 204`() {
        every { subscriptions.get(endpoint) } returns PushSubscriptionDto(
            endpoint = endpoint, discordId = discordId,
            p256dh = "pk", auth = "auth",
            userAgent = null, createdAt = Instant.now(), lastUsedAt = null,
        )

        val resp = controller.unsubscribe(
            PushSubscriptionController.UnsubscribeRequest(endpoint), user
        )
        assertEquals(204, resp.statusCode.value())
        verify(exactly = 1) { subscriptions.unsubscribe(endpoint) }
    }

    @Test
    fun `unsubscribe on a non-existent endpoint is still 204 (idempotent)`() {
        every { subscriptions.get(endpoint) } returns null

        val resp = controller.unsubscribe(
            PushSubscriptionController.UnsubscribeRequest(endpoint), user
        )
        assertEquals(204, resp.statusCode.value())
        verify(exactly = 1) { subscriptions.unsubscribe(endpoint) }
    }

    // ---------- list ----------

    @Test
    fun `list rejects unauthenticated with 401`() {
        val anon = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }
        val resp = controller.list(anon)
        assertEquals(401, resp.statusCode.value())
        verify(exactly = 0) { subscriptions.listForUser(any()) }
    }

    @Test
    fun `list returns subscriptions for the authenticated user only`() {
        every { subscriptions.listForUser(discordId) } returns listOf(
            PushSubscriptionDto(
                endpoint = "$endpoint/laptop", discordId = discordId,
                p256dh = "pk", auth = "a",
                userAgent = "Firefox", createdAt = Instant.parse("2026-05-19T10:00:00Z"),
                lastUsedAt = null,
            ),
            PushSubscriptionDto(
                endpoint = "$endpoint/phone", discordId = discordId,
                p256dh = "pk", auth = "a",
                userAgent = "Chrome", createdAt = Instant.parse("2026-05-19T11:00:00Z"),
                lastUsedAt = Instant.parse("2026-05-19T12:00:00Z"),
            ),
        )

        val resp = controller.list(user)
        assertEquals(200, resp.statusCode.value())
        val body = resp.body!!
        assertEquals(2, body.size)
        assertTrue(body.any { it.endpoint == "$endpoint/laptop" && it.userAgent == "Firefox" })
        val phone = body.first { it.endpoint == "$endpoint/phone" }
        assertEquals("Chrome", phone.userAgent)
        assertNotNull(phone.lastUsedAt)
    }
}
