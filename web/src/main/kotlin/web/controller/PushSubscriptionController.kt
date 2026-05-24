package web.controller

import common.notification.PushAdapter
import common.notification.PushPayload
import database.service.user.PushSubscriptionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
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
    @Autowired(required = false) private val pushAdapter: PushAdapter? = null,
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

    /**
     * Self-serve smoke test for the push pipeline. Bypasses the per-(kind,
     * surface) opt-in check on purpose — the caller is explicitly asking to
     * receive a test push, so honouring their pref state would be a UX trap
     * for anyone trying to verify "is web push wired correctly for me?".
     *
     * Outcomes the caller cares about, mapped to status codes:
     *  - 401 — not authenticated.
     *  - 503 — server has no PushAdapter bean (VAPID env vars absent).
     *  - 200 ok=false — adapter is wired but the user has no persisted
     *    subscription, so there's nothing to deliver to. The UI surfaces
     *    the "click Enable browser push first" message.
     *  - 200 ok=true — payload handed off to the adapter; the browser
     *    notification should appear shortly.
     *  - 500 — adapter threw; the message includes the exception so the
     *    operator can read it directly in the response.
     */
    @PostMapping("/test")
    fun sendTestPush(
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<TestPushResponse> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        val adapter = pushAdapter
            ?: return ResponseEntity.status(503).body(
                TestPushResponse(
                    ok = false,
                    adapterPresent = false,
                    subscriptionCount = 0,
                    message = "Push adapter not configured on the server. " +
                        "Set TOBY_VAPID_PUBLIC_KEY and TOBY_VAPID_PRIVATE_KEY.",
                )
            )
        val subs = subscriptions.listForUser(discordId)
        if (subs.isEmpty()) {
            return ResponseEntity.ok(
                TestPushResponse(
                    ok = false,
                    adapterPresent = true,
                    subscriptionCount = 0,
                    message = "No browser subscriptions registered for this account. " +
                        "Click 'Enable browser push' first.",
                )
            )
        }
        val payload = PushPayload(
            title = "Test notification",
            body = "If you see this, web push is working ✓",
            deepLink = null,
        )
        return runCatching { adapter.deliver(discordId, payload) }
            .map {
                ResponseEntity.ok(
                    TestPushResponse(
                        ok = true,
                        adapterPresent = true,
                        subscriptionCount = subs.size,
                        message = "Test push dispatched to ${subs.size} endpoint(s).",
                    )
                )
            }
            .getOrElse { err ->
                ResponseEntity.status(500).body(
                    TestPushResponse(
                        ok = false,
                        adapterPresent = true,
                        subscriptionCount = subs.size,
                        message = "Push delivery threw: ${err.message ?: err::class.simpleName}",
                    )
                )
            }
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

    data class TestPushResponse(
        val ok: Boolean,
        val adapterPresent: Boolean,
        val subscriptionCount: Int,
        val message: String,
    )
}
