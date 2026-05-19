package bot.toby.notify

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration

/**
 * VAPID (RFC 8292) keys + contact subject used by [WebPushAdapter] to
 * sign web-push delivery requests. Both keys are base64url-encoded
 * raw P-256 EC bytes (uncompressed point for the public key, scalar for
 * the private key) — the format the `web-push-libs` tool family emits.
 *
 * The keys are environment-supplied at deploy time. When unset the
 * [WebPushAdapter] bean isn't registered (see
 * [@ConditionalOnProperty][ConditionalOnProperty] on
 * `WebPushAdapterAutoConfig`), so the router falls back to its no-op
 * + warn path. This makes dev/CI environments without VAPID keys
 * continue to work without surprise dependency on outbound network.
 *
 * Subject is required by RFC 8292 §2.1 — `mailto:` or HTTPS URL pointing
 * to the operator's contact. Push services use it to reach you when
 * something goes wrong with delivery for your origin.
 */
@Configuration
@ConditionalOnProperty(prefix = "toby.vapid", name = ["public-key", "private-key"])
class VapidProperties(
    @Value("\${toby.vapid.public-key}") val publicKey: String,
    @Value("\${toby.vapid.private-key}") val privateKey: String,
    @Value("\${toby.vapid.subject:mailto:admin@example.invalid}") val subject: String,
)
