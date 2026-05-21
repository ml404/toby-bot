package web.controller

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.service.SseRegistrar
import web.util.discordIdOrNull

/**
 * Opens the per-user SSE stream that delivers engagement notifications
 * as in-page toasts. Authentication is enforced by the Spring Security
 * config — an unauthenticated principal (or one whose Discord id can't
 * be parsed) gets 401 and the client's reconnect logic gives up.
 */
@RestController
class NotificationSseController(
    private val registrar: SseRegistrar,
) {

    @GetMapping("/api/notifications/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@AuthenticationPrincipal user: OAuth2User?): ResponseEntity<SseEmitter> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(registrar.register(discordId))
    }
}
