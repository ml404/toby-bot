package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.service.SseRegistrar

class NotificationSseControllerTest {

    private val registrar: SseRegistrar = mockk(relaxed = true)
    private val controller = NotificationSseController(registrar)

    @Test
    fun `null principal returns 401 and does not register an emitter`() {
        val response = controller.stream(user = null)
        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { registrar.register(any()) }
    }

    @Test
    fun `principal with missing id attribute returns 401`() {
        val user = mockk<OAuth2User> {
            every { getAttribute<String>("id") } returns null
        }
        val response = controller.stream(user = user)
        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { registrar.register(any()) }
    }

    @Test
    fun `principal with non-numeric id attribute returns 401`() {
        val user = mockk<OAuth2User> {
            every { getAttribute<String>("id") } returns "not-a-number"
        }
        val response = controller.stream(user = user)
        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { registrar.register(any()) }
    }

    @Test
    fun `authenticated principal returns the emitter the registrar produced`() {
        val emitter = mockk<SseEmitter>(relaxed = true)
        val user = mockk<OAuth2User> {
            every { getAttribute<String>("id") } returns "1234567890"
        }
        every { registrar.register(1234567890L) } returns emitter

        val response = controller.stream(user = user)

        assertEquals(200, response.statusCode.value())
        assertEquals(emitter, response.body)
        verify(exactly = 1) { registrar.register(1234567890L) }
    }

    @Test
    fun `RequestMapping is the canonical notifications stream path with SSE content type`() {
        // Reflect the controller's mapping so any rename to the path or
        // content type is caught here as a regression.
        val method = NotificationSseController::class.java.getDeclaredMethod(
            "stream",
            OAuth2User::class.java,
        )
        val mapping = method.annotations.filterIsInstance<
            org.springframework.web.bind.annotation.GetMapping
            >().single()
        assertEquals(
            listOf("/api/notifications/stream").toString(),
            mapping.value.toList().toString(),
        )
        assertEquals(
            listOf(MediaType.TEXT_EVENT_STREAM_VALUE).toString(),
            mapping.produces.toList().toString(),
        )
    }

    @Test
    fun `controller exposes a body of type SseEmitter`() {
        val emitter = mockk<SseEmitter>(relaxed = true)
        val user = mockk<OAuth2User> {
            every { getAttribute<String>("id") } returns "42"
        }
        every { registrar.register(42L) } returns emitter
        val response = controller.stream(user = user)
        assertNotNull(response.body)
        assertEquals(emitter, response.body)
    }
}
