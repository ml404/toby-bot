package bot.toby.notify

import com.fasterxml.jackson.databind.ObjectMapper
import common.logging.DiscordLogger
import common.notification.PushPayload
import database.service.PushSubscriptionService
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Subscription
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component
import java.security.Security

/**
 * Web Push (RFC 8030 / 8291 / 8292) implementation of [PushAdapter].
 *
 * Fans [PushAdapter.deliver] out across every persisted subscription
 * for the user, serialising the [PushPayload] to a compact JSON
 * envelope the in-browser service worker (`/sw.js`) consumes:
 *
 * ```json
 * {"title":"...","body":"...","deepLink":"..."}
 * ```
 *
 * Per-endpoint errors:
 *  - 404 / 410 → the subscription is dead (browser unsubscribed,
 *    permissions revoked, etc.). We delete it so we stop trying.
 *  - 429 / 5xx → transient. Logged and skipped — the next push will
 *    retry naturally.
 *
 * Bean wiring: this class is `@ConditionalOnBean(VapidProperties::class)`,
 * which itself is `@ConditionalOnProperty` on `toby.vapid.public-key` /
 * `private-key`. Without keys → no bean → `NotificationRouter`'s
 * `@Autowired(required=false)` adapter stays null → router falls back
 * to its no-op + warn path. Dev environments work unchanged.
 *
 * BouncyCastle is registered eagerly at construction because
 * `PushService` requires it for EC key parsing and AES-128-GCM encryption.
 * The registration is idempotent — `addProvider` is a no-op if a provider
 * with the same name is already installed.
 *
 * The actual network call is mediated through [PushTransport] so a unit
 * test can swap in a fake response without spinning up a real push
 * service. Production wiring uses [DefaultPushTransport] (the only
 * other implementation today).
 */
@Component
@ConditionalOnBean(VapidProperties::class)
class WebPushAdapter(
    private val vapid: VapidProperties,
    private val subscriptions: PushSubscriptionService,
    private val objectMapper: ObjectMapper,
    private val transport: PushTransport = DefaultPushTransport(vapid),
) : PushAdapter {

    private val logger = DiscordLogger.createLogger(this::class.java)

    override fun deliver(discordId: Long, payload: PushPayload) {
        val targets = runCatching { subscriptions.listForUser(discordId) }
            .getOrElse {
                logger.warn("WebPushAdapter: failed to look up subscriptions for $discordId: ${it.message}")
                return
            }
        if (targets.isEmpty()) return

        val body = runCatching {
            objectMapper.writeValueAsBytes(
                mapOf(
                    "title" to payload.title,
                    "body" to payload.body,
                    "deepLink" to payload.deepLink,
                )
            )
        }.getOrElse {
            logger.warn("WebPushAdapter: payload serialise failed: ${it.message}")
            return
        }

        targets.forEach { sub ->
            val status = runCatching { transport.send(sub.endpoint, sub.p256dh, sub.auth, body) }
                .getOrElse { err ->
                    logger.warn("WebPushAdapter: send threw for ${sub.endpoint}: ${err.message}")
                    return@forEach
                }
            when {
                status in 200..299 -> {
                    runCatching { subscriptions.markUsed(sub.endpoint) }
                        .onFailure { logger.warn("markUsed failed for ${sub.endpoint}: ${it.message}") }
                }
                status == 404 || status == 410 -> {
                    logger.info { "Push endpoint ${sub.endpoint} reported $status; pruning subscription." }
                    runCatching { subscriptions.unsubscribe(sub.endpoint) }
                        .onFailure { logger.warn("unsubscribe failed for ${sub.endpoint}: ${it.message}") }
                }
                else -> {
                    logger.warn("Push to ${sub.endpoint} returned HTTP $status; will retry on next push.")
                }
            }
        }
    }
}

/**
 * Single network-call indirection so [WebPushAdapter] is unit-testable
 * without spinning up an actual push service. Returns the HTTP status
 * code; non-2xx statuses are recoverable signalling (410=gone), so the
 * caller branches on them rather than throwing.
 */
interface PushTransport {
    fun send(endpoint: String, p256dh: String, auth: String, body: ByteArray): Int
}

/**
 * Production [PushTransport] backed by martijndwars's web-push library.
 * Lazy-initialises [PushService] so failed VAPID parsing (e.g. an
 * operator pastes the wrong key) defers the throw to the first push
 * rather than crashing the application context.
 */
class DefaultPushTransport(private val vapid: VapidProperties) : PushTransport {

    private val pushService: PushService by lazy {
        Security.addProvider(BouncyCastleProvider())
        PushService(vapid.publicKey, vapid.privateKey, vapid.subject)
    }

    override fun send(endpoint: String, p256dh: String, auth: String, body: ByteArray): Int {
        val notification = Notification(
            Subscription(endpoint, Subscription.Keys(p256dh, auth)),
            body,
        )
        val response = pushService.send(notification)
        return response.statusLine.statusCode
    }
}
