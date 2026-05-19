package web.controller

import database.service.PushSubscriptionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import web.util.discordIdOrNull
import java.time.Instant

/**
 * JSON-only REST surface for browser-side web-push lifecycle:
 *
 *   GET    /api/push/vapid-public-key   — returns the server's VAPID public
 *          key the browser passes to `pushManager.subscribe(...)`. Available
 *          even when the operator hasn't deployed a key (returns 404) so
 *          the client can detect "push isn't configured" without a 500.
 *   POST   /api/push/subscribe           — persist a new subscription against
 *          the authenticated user. Idempotent on endpoint.
 *   DELETE /api/push/subscribe           — body `{endpoint}` — drop the row.
 *          Also called by the browser when permission is revoked.
 *   GET    /api/push/subscriptions       — list the user's currently-registered
 *          devices for the "Enabled devices" list on the prefs page.
 *
 * No per-guild scoping here: a push subscription is a *device* anchor.
 * Per-(kind, surface) opt-in lives on `/api/engagement/{guildId}/...`
 * and stays per-guild. That mirrors how each guild's notifications can
 * be tuned independently while the subscriber identity is unified.
 */
@RestController
@RequestMapping("/api/push")
class PushSubscriptionController(
    private val subscriptions: PushSubscriptionService,
    @Value("\${toby.vapid.public-key:}") private val vapidPublicKey: String = "",
) {

    @GetMapping("/vapid-public-key")
    fun vapidPublicKey(): ResponseEntity<VapidKeyResponse> =
        if (vapidPublicKey.isBlank()) ResponseEntity.notFound().build()
        else ResponseEntity.ok(VapidKeyResponse(publicKey = vapidPublicKey))

    @PostMapping("/subscribe")
    fun subscribe(
        @RequestBody body: SubscribeRequest,
        request: HttpServletRequest,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<SubscriptionResponse> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (body.endpoint.isBlank() || body.p256dh.isBlank() || body.auth.isBlank()) {
            return ResponseEntity.badRequest().build()
        }
        val row = subscriptions.subscribe(
            discordId = discordId,
            endpoint = body.endpoint,
            p256dh = body.p256dh,
            auth = body.auth,
            userAgent = request.getHeader("User-Agent")?.take(512),
            at = Instant.now(),
        )
        return ResponseEntity.ok(
            SubscriptionResponse(
                endpoint = row.endpoint,
                userAgent = row.userAgent,
                createdAt = row.createdAt.toString(),
                lastUsedAt = row.lastUsedAt?.toString(),
            )
        )
    }

    @DeleteMapping("/subscribe")
    fun unsubscribe(
        @RequestBody body: UnsubscribeRequest,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<Void> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (body.endpoint.isBlank()) return ResponseEntity.badRequest().build()
        // Refuse to drop someone else's subscription even if the caller
        // somehow learned the endpoint URL. Service-level scoping is
        // simpler than a per-endpoint ownership column query.
        val owner = subscriptions.get(body.endpoint)
        if (owner != null && owner.discordId != discordId) {
            return ResponseEntity.status(403).build()
        }
        subscriptions.unsubscribe(body.endpoint)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/subscriptions")
    fun list(
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<List<SubscriptionResponse>> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        val rows = subscriptions.listForUser(discordId).map {
            SubscriptionResponse(
                endpoint = it.endpoint,
                userAgent = it.userAgent,
                createdAt = it.createdAt.toString(),
                lastUsedAt = it.lastUsedAt?.toString(),
            )
        }
        return ResponseEntity.ok(rows)
    }

    data class VapidKeyResponse(val publicKey: String)

    data class SubscribeRequest(
        val endpoint: String,
        val p256dh: String,
        val auth: String,
    )

    data class UnsubscribeRequest(val endpoint: String)

    data class SubscriptionResponse(
        val endpoint: String,
        val userAgent: String?,
        val createdAt: String,
        val lastUsedAt: String?,
    )
}
