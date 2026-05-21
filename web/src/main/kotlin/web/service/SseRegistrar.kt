package web.service

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Minimal surface a controller needs to subscribe a user to an SSE
 * stream. Keeps controllers from coupling to the wider event-listener
 * surface of [NotificationSseService] (Interface Segregation) so a
 * test can stub the registrar with a one-method fake.
 */
fun interface SseRegistrar {
    fun register(discordId: Long): SseEmitter
}
